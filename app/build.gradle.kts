plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aakash.pods"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aakash.pods"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    // Optional permanent signing key, supplied from the environment (never stored in the repo).
    // Without it, builds are signed with the standard debug key, which installs fine.
    val keystorePath: String? = System.getenv("PODS_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) {
            create("pods") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("PODS_STORE_PASSWORD")
                keyAlias = System.getenv("PODS_KEY_ALIAS") ?: "pods"
                keyPassword = System.getenv("PODS_KEY_PASSWORD") ?: System.getenv("PODS_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("pods") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
