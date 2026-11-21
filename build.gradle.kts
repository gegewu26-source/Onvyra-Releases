// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
}

// b/549552303: RectManager can crash while resolving alignment lines in reused
// layouts. Keep the mutually dependent core Compose libraries on the fixed release,
// including test configurations; the Material 3 Expressive version stays unchanged.
val composeCoreVersion = libs.versions.composeCore.get()
allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group in setOf("androidx.compose.ui", "androidx.compose.runtime",
                    "androidx.compose.foundation", "androidx.compose.animation")) {
                useVersion(composeCoreVersion)
                because("Fix LayoutNode not found in RectList (b/549552303) with aligned Compose core libraries")
            }
        }
    }
}
