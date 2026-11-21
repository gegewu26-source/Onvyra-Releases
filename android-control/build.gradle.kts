import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    id("onvyra.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.androidcontrol"
    // AIDL's Windows depfile uses the system code page for non-ASCII paths. Generate the
    // normal SDK Binder binding without a depfile, and normalize only its generated header.
    buildFeatures { aidl = false }
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
}

abstract class GenerateControlBinder @Inject constructor(private val execution: ExecOperations) : DefaultTask() {
    @get:InputFile abstract val contract: RegularFileProperty
    @get:InputFile abstract val compiler: RegularFileProperty
    @get:InputFile abstract val framework: RegularFileProperty
    @get:OutputDirectory abstract val output: DirectoryProperty
    @TaskAction fun generate() {
        val root=output.get().asFile.apply {mkdirs()}
        execution.exec {
            val input=contract.get().asFile
            val imports=input.parentFile.parentFile.parentFile.parentFile.parentFile
            commandLine(compiler.get().asFile.absolutePath,"-p${framework.get().asFile.absolutePath}","-I${imports.absolutePath}","-o${root.absolutePath}",input.absolutePath)
        }.assertNormalExitValue()
        root.walkTopDown().filter {it.extension=="java"}.forEach { file ->
            val bytes=file.readBytes()
            val source=bytes.toString(Charsets.ISO_8859_1)
            val start=source.indexOf("package me.rerere.androidcontrol.shell;")
            check(start>=0)
            // The AIDL contract's generated body is ASCII; drop machine-specific command paths.
            file.writeText("// Generated from IAndroidShell.aidl by the Android SDK AIDL compiler.\n"+source.substring(start),Charsets.UTF_8)
        }
    }
}
val sdkDirectory=providers.environmentVariable("ANDROID_HOME").orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
val generateControlBinder=tasks.register<GenerateControlBinder>("generateControlBinder") {
    contract.set(layout.projectDirectory.file("src/main/aidl/me/rerere/androidcontrol/shell/IAndroidShell.aidl"))
    val windows=System.getProperty("os.name").startsWith("Windows",true)
    compiler.set(layout.file(sdkDirectory.map {file("$it/build-tools/37.0.0/aidl"+(if(windows)".exe" else ""))}))
    framework.set(layout.file(sdkDirectory.map {file("$it/platforms/android-37.0/framework.aidl")}))
    output.set(layout.buildDirectory.dir("generated/control-binder"))
}
androidComponents.onVariants { variant ->
    variant.sources.java?.addGeneratedSourceDirectory(generateControlBinder,GenerateControlBinder::output)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    api(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // ddddocr's bundled OCR graph runs offline on-device; the model + charset
    // live under src/main/assets/captcha.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation("androidx.test:runner:1.7.0")
}
