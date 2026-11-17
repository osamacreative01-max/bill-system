plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.mashallah.billing"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mashallah.billing"
        minSdk = 23
        targetSdk = 35
        versionCode = 3
        versionName = "0.3"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
