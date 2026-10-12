plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- App version: the ONLY place to change it ----------------------------------
// MAJOR.MINOR.PATCH  ->  bugfix: 1.3.0 -> 1.3.1 · new feature: 1.3.1 -> 1.4.0 · big change: 2.0.0
// GitHub Actions reads this line to name the release (v1.3.1).
val appVersion = "1.3.0"
// versionCode is derived (1.3.0 -> 10300) so it always goes up when the version does.
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { (ma, mi, pa) -> ma * 10000 + mi * 100 + pa }

// Signing key: app/qrlens.keystore (kept OUT of git, see .gitignore).
// CI recreates it from the KEYSTORE_BASE64 secret. Without it, builds fall back to the debug key.
val keystoreFile = file("qrlens.keystore")
val keystorePass = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotEmpty() } ?: "qrlens123"

android {
    namespace = "com.qrlens.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.qrlens.app"
        minSdk = 30          // Android 11+ (needed for accessibility screenshots)
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                storeType = "pkcs12"
                storePassword = keystorePass
                keyAlias = "qrlens"
                keyPassword = keystorePass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // On-device QR detection (pure Java, works offline)
    implementation("com.google.zxing:core:3.5.3")
}
