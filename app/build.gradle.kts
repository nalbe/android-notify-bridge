plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bastet.notifybridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bastet.notifybridge"
        minSdk = 29
        targetSdk = 34
        versionCode = 6
        versionName = "6.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

base {
    archivesName.set("notifybridge")
}

dependencies {
}
