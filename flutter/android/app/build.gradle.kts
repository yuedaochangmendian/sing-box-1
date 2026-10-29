plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "io.github.yuedaochangmendian.skybox"
    // shared_preferences_android (and the AGP 9 baseline) require API 36 to compile.
    compileSdk = 36
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "io.github.yuedaochangmendian.skybox"
        minSdk = 24
        targetSdk = 35
        // Version comes from pubspec.yaml (flutter.versionCode/versionName are
        // injected by the Flutter Gradle plugin); fallbacks match the current release.
        versionCode = flutter.versionCode ?: 9
        versionName = flutter.versionName ?: "3.0.2"
        ndk {
            // Release ships arm64 only; pass -PskyboxAbi=x86_64 for local emulator builds.
            abiFilters += listOf(project.findProperty("skyboxAbi") as? String ?: "arm64-v8a")
        }
    }

    buildTypes {
        release {
            // Local/testing release: uses the same default debug key as debug.
            // Replace with a private, stable signing configuration before distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // sing-box Go core, bound with gomobile. Dart cannot bind a gomobile archive directly,
    // so the kernel stays on this side and the Flutter UI talks to it over platform channels.
    implementation(files("libs/libbox.aar"))
}

flutter {
    source = "../.."
}
