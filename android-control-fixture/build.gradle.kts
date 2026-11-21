plugins { alias(libs.plugins.android.application) }
android {
    namespace = "me.rerere.androidcontrol.fixture"
    compileSdk = 37
    defaultConfig { applicationId = "me.rerere.androidcontrol.fixture"; minSdk = 34; targetSdk = 37; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
