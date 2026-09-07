plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.ev0lv3nta.dictate"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ev0lv3nta.dictate"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("integration") {
            initWith(getByName("debug"))
            matchingFallbacks += "debug"
        }
        release {
            isMinifyEnabled = false
        }
    }

    sourceSets {
        getByName("debug").java.srcDir("src/production/java")
        getByName("release").java.srcDir("src/production/java")
    }
    buildFeatures { buildConfig = true }
    testBuildType = "integration"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
