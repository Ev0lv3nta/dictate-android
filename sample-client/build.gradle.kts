plugins { id("com.android.application") }
android {
    namespace = "io.github.ev0lv3nta.dictate.sample"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.ev0lv3nta.dictate.sample"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
