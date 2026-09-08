plugins {
    id("com.android.application")
}

android {
    namespace = "com.antisleep.keepscreen"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.antisleep.keepscreen"
        minSdk = 34
        targetSdk = 36
        versionCode = 4
        versionName = "1.4"
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

    buildFeatures {
        buildConfig = false
        aidl = false
    }
}

dependencies {
}
