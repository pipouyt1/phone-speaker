plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.phonespeaker.lite"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.phonespeaker.lite"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0-lite"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
