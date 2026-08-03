plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.example.nosmai_agora_bridge_example"
    compileSdk = flutter.compileSdkVersion
    // Pinned rather than flutter.ndkVersion: that resolves to 28.2.13676358,
    // which is present in the SDK but CORRUPT (no source.properties), so
    // configuration fails with CXX1101 before anything compiles. 29.0.14206865
    // is a complete install and is what the Nosmai SDK itself is built with.
    ndkVersion = "29.0.14206865"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.example.nosmai_agora_bridge_example"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    // The camera SDK plugin declares this AAR compileOnly, which keeps the 62MB
    // binary out of the pub.dev package but means it is NOT packaged into the
    // app. The host has to add it as a real (runtime) dependency or the app
    // builds and then crashes on first native call with UnsatisfiedLinkError.
    implementation(files("libs/nosmai-release.aar"))
}
