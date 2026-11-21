import com.android.build.api.dsl.Packaging
import groovy.json.JsonOutput
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.Properties
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.baselineprofile)
}

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

fun signingCertificatePin(store: java.io.File?, password: String?, alias: String?, type: String?): String {
    if (store == null || !store.isFile || alias.isNullOrBlank()) return ""
    return runCatching {
        val keyStore = KeyStore.getInstance(type ?: KeyStore.getDefaultType())
        store.inputStream().use { keyStore.load(it, password?.toCharArray()) }
        sha256Hex(requireNotNull(keyStore.getCertificate(alias)).encoded)
    }.getOrElse { throw GradleException("Unable to read the configured signing certificate. Check the external signing properties.") }
}

// Local builds do not require the upstream project's private Firebase configuration.
if (file("google-services.json").exists() ||
    fileTree("src").matching { include("**/google-services.json") }.files.isNotEmpty()
) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

val privateVideoEndpoints = providers.environmentVariable("ONVYRA_PRIVATE_VIDEO_ENDPOINTS")
    .filter { it.isNotBlank() }
    .map { path ->
        val config = rootProject.file(path)
        require(config.isFile && config.length() <= 16 * 1024) { "Invalid private video endpoint configuration file." }
        config.readText()
    }
    .orElse("{}")

android {
    namespace = "com.constx.onvyra"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.constx.onvyra"
        minSdk = 26
        targetSdk = 37
        versionCode = 263
        versionName = "2.7.2-reader.72"

        buildConfigField("String", "PRIVATE_VIDEO_ENDPOINTS_JSON", JsonOutput.toJson(privateVideoEndpoints.get()))
        buildConfigField("String", "PRIVATE_MEDIA_ASSET_SHA256", "\"${sha256Hex(file("src/main/assets/security/private_media_access.json").readBytes())}\"")
        buildConfigField("String", "BUILTIN_MODE_ASSET_SHA256", "\"${sha256Hex(file("src/main/assets/mode_injections/wuzha.txt").readBytes())}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    splits {
        abi {
            // AppBundle tasks usually contain "bundle" in their name
            //noinspection WrongGradleMethod
            val isBuildingBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
            isEnable = !isBuildingBundle
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            val localProperties = Properties()
            // Credentials stay outside source archives. A command-scoped environment variable
            // points to the owner's protected properties file for formal distribution builds.
            val externalSigningFile = providers.environmentVariable("ONVYRA_SIGNING_PROPERTIES").orNull
            val localPropertiesFile = externalSigningFile?.let { rootProject.file(it) }
                ?: rootProject.file("local.properties")

            if (localPropertiesFile.exists()) {
                localProperties.load(FileInputStream(localPropertiesFile))

                val storeFilePath = localProperties.getProperty("storeFile")
                val storePasswordValue = localProperties.getProperty("storePassword")
                val keyAliasValue = localProperties.getProperty("keyAlias")
                val keyPasswordValue = localProperties.getProperty("keyPassword")

                if (storeFilePath != null && storePasswordValue != null &&
                    keyAliasValue != null && keyPasswordValue != null
                ) {
                    storeFile = file(storeFilePath)
                    storePassword = storePasswordValue
                    keyAlias = keyAliasValue
                    keyPassword = keyPasswordValue
                    storeType = localProperties.getProperty("storeType", "PKCS12")
                }
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            buildConfigField("boolean", "APP_INTEGRITY_ENFORCED", "true")
            val releaseSigner = signingConfigs.getByName("release")
            val releasePin = signingCertificatePin(releaseSigner.storeFile, releaseSigner.storePassword, releaseSigner.keyAlias, releaseSigner.storeType)
            buildConfigField("String", "TRUSTED_SIGNING_CERT_SHA256", "\"$releasePin\"")
            optimization {
                enable = true
                keepRules {
                    files.add(file("proguard-video.pro"))
                }
            }
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
        }
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "APP_INTEGRITY_ENFORCED", "false")
            buildConfigField("String", "TRUSTED_SIGNING_CERT_SHA256", "\"\"")
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
        }
        create("optimized") {
            initWith(getByName("release"))
            // Onvyra has its own public app identity. Debug builds remain separate for QA.
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            val testSigner = signingConfigs.getByName("debug")
            val testPin = signingCertificatePin(testSigner.storeFile, testSigner.storePassword, testSigner.keyAlias, testSigner.storeType)
            buildConfigField("boolean", "APP_INTEGRITY_ENFORCED", "true")
            buildConfigField("String", "TRUSTED_SIGNING_CERT_SHA256", "\"$testPin\"")
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }
    androidResources {
        generateLocaleConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "lib/*/libtermux.so"
        }
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        compilerOptions.optIn.add("androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalAnimationApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalSharedTransitionApi")
        compilerOptions.optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        compilerOptions.optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        compilerOptions.optIn.add("androidx.navigation3.runtime.ExperimentalNavigation3Api")
    }
}

// Fail while building, rather than shipping a package which silently lacks a trust pin
// or was signed with the QA identity. No credentials or private key material are logged.
val validateReleaseTrust = tasks.register("validateReleaseTrust") {
    group = "verification"
    description = "Verify the formal release signing identity before packaging"
    doLast {
        val releaseSigner = android.signingConfigs.getByName("release")
        val releasePin = signingCertificatePin(releaseSigner.storeFile, releaseSigner.storePassword,
            releaseSigner.keyAlias, releaseSigner.storeType)
        val debugSigner = android.signingConfigs.getByName("debug")
        val debugPin = signingCertificatePin(debugSigner.storeFile, debugSigner.storePassword,
            debugSigner.keyAlias, debugSigner.storeType)
        check(releasePin.length == 64) { "Formal release signing is missing. Set ONVYRA_SIGNING_PROPERTIES to the protected owner configuration." }
        check(releasePin != debugPin) { "Formal releases must not use the debug signing certificate." }
        check(releasePin != "fcb7a76ad390ffeb7ceb5c9e6efe74db3fd531ea6aef51f804fe76a487b13482") {
            "The previous QA certificate cannot be used as a formal release identity."
        }
        val release = android.buildTypes.getByName("release")
        check(!release.isDebuggable) { "Formal releases must not permit debugging." }
    }
}
androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    validateReleaseTrust.configure {
        doLast {
            val fields = checkNotNull(variant.buildConfigFields).get()
            check(fields["APP_INTEGRITY_ENFORCED"]?.value?.toString() == "true") { "Release package integrity enforcement is required." }
            val signer = android.signingConfigs.getByName("release")
            val expected = signingCertificatePin(signer.storeFile, signer.storePassword, signer.keyAlias, signer.storeType)
            check(fields["TRUSTED_SIGNING_CERT_SHA256"]?.value?.toString() == "\"$expected\"") { "The release trust pin must match its signing identity." }
        }
    }
}
tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(validateReleaseTrust)
}

composeCompiler {
    stabilityConfigurationFiles.add(
        project.layout.projectDirectory.file("compose_compiler_config.conf")
    )
}

baselineProfile {
    automaticGenerationDuringBuild = false
}

tasks.register("buildAll") {
    dependsOn("assembleRelease", "bundleRelease")
    description = "Build both APK and AAB"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Local JVM tests need the desktop native library instead of the Android AAR.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjs.get()}"))
    }
}

dependencies {
    implementation(libs.jsoup)
    // Legado-compatible JavaScript source rules execute in a restricted Rhino scope.
    implementation("org.mozilla:rhino:1.7.15")
    implementation(project(":android-control"))
    implementation(project(":netdisk-download-engine"))
    implementation(libs.quickjs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.termux.terminal.view)
    implementation(libs.guava.listenablefuture)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)

    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.material3.adaptive.navigation3)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Image metadata extractor
    // https://github.com/drewnoakes/metadata-extractor
    implementation(libs.metadata.extractor)

    // Haze (background blur and glass)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.haze.blur.material3)
    implementation(libs.haze.glass)
    implementation(libs.haze.glass.material3)

    // koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.androidx.workmanager)

    // okhttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization.json)

    // ktor client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // ucrop
    implementation(libs.ucrop)

    // pebble (template engine)
    implementation(libs.pebble)

    // java-diff-utils (unified diff)
    implementation(libs.diffutils)

    // coil
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.coil.cache.control)

    // serialization
    implementation(libs.kotlinx.serialization.json)

    // YAML front matter
    implementation(libs.snakeyaml)

    // zxing
    implementation(libs.zxing.core)

    // quickie (qrcode scanner)
    implementation(libs.quickie.bundled)
    implementation(libs.barcode.scanning)
    implementation(libs.androidx.camera.core)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    baselineProfile(project(":app:baselineprofile"))
    ksp(libs.androidx.room.compiler)

    // Paging3
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // media3 (video playback)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.webkit)
    implementation(libs.youtubedl.android)

    // Apache Commons Text
    implementation(libs.commons.text)

    // Reorderable (https://github.com/Calvin-LL/Reorderable/)
    implementation(libs.reorderable)

    // icons
    implementation(libs.huge.icons)

    // image viewer
    implementation(libs.image.viewer)

    // JLatexMath
    // https://github.com/rikkahub/jlatexmath-android
    implementation(libs.jlatexmath)
    implementation(libs.jlatexmath.font.greek)
    implementation(libs.jlatexmath.font.cyrillic)

    // mcp
    implementation(libs.modelcontextprotocol.kotlin.sdk)

    // jmDNS (mDNS/Bonjour for .local hostname)
    implementation(libs.jmdns)

    // SLF4J Android binding — routes Ktor/SLF4J logs to logcat
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.android)

    // sqlite-android (requery SQLite for Android)
    implementation(libs.sqlite.android)

    // modules
    implementation(project(":ai"))
    implementation(project(":web"))
    implementation(project(":document"))
    implementation(project(":highlight"))
    implementation(project(":search"))
    implementation(project(":speech"))
    implementation(project(":mediagen"))
    implementation(project(":common"))
    implementation(project(":material3"))
    implementation(project(":ui"))
    implementation(project(":workspace"))
    implementation(project(":oauth"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(kotlin("reflect"))

    // Leak Canary
    // debugImplementation(libs.leakcanary.android)

    // tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
