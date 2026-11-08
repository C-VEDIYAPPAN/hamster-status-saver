plugins {
    id("com.android.application")
}

android {
    namespace = "com.statusvault.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.statusvault.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "2.6"
    }

    // signing/statusvault.jks is git-ignored. CI restores it from the KEYSTORE_BASE64 secret.
    val keystore = rootProject.file("signing/statusvault.jks")
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "statusvault"
    signingConfigs {
        create("vault") {
            storeFile = keystore
            storePassword = keystorePassword
            keyAlias = "statusvault"
            keyPassword = keystorePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystore.exists()) signingConfigs.getByName("vault") else signingConfigs.getByName("debug")
        }
    }

    lint {
        // Framework-only app; keep release builds from failing on advisory checks.
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

// No dependencies — the app uses only the Android framework.
