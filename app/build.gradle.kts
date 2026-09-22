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
        versionCode = 5
        versionName = "5.0.0"
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

// Stage the release APK into the project-level release/ folder (tracked,
// see .gitignore) right after the release build finishes. Only the
// primary release APK is staged, under its real name. The parallel
// "-debugkey" artifact AGP emits for a debug-signed release is never
// copied. Debug builds are NOT staged.
afterEvaluate {
    val copyTask = tasks.register<Copy>("stageReleaseApk") {
        group = "build"
        description = "Copy the release APK to the project release/ folder"
        from(layout.buildDirectory.dir("outputs/apk/release"))
        include("notifybridge-release.apk")
        into(rootProject.layout.projectDirectory.dir("release"))
    }
    tasks.named("assembleRelease") { finalizedBy(copyTask) }
}

dependencies {
}
