plugins { id("com.android.application") }
android {
    namespace = "io.github.ev0lv3nta.dictate.sample"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.ev0lv3nta.dictate.sample"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
