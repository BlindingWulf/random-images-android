import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing material lives outside the repo (see keystore.properties.example).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.blindingwulf.randomimages"
    // androidx.core 1.19 / Material 1.14 refuse to build against < 37; AGP 9.5
    // supports up to API 37. targetSdk stays on 36 (the phone's Android 16).
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.blindingwulf.randomimages"
        // Android 5.0. Lowest floor that still gets TLS 1.2 + ALPN by default;
        // the app also ships its own CA bundle because 5.x-7.x root stores
        // cannot validate FurAffinity's Let's Encrypt chain.
        minSdk = 21
        targetSdk = 36
        versionCode = 4
        versionName = "1.1.1"
    }

    androidResources {
        localeFilters += listOf("en")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    lint {
        // NewApi already defaults to error, so `gradle lint` is the gate that
        // keeps every call legal on API 21. Fail rather than ship a crash.
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    // Pinned for the API 21 floor: core-ktx 1.19 and Material 1.14 declare
    // minSdk 23 (verified from their AAR manifests); these do not.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    // repeatOnLifecycle / lifecycleScope for the state collect in MainActivity.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // No security-crypto: EncryptedSharedPreferences needs API 23. Credentials
    // are sealed with the AndroidKeyStore directly (SecurePrefs), which works
    // from API 21 via the legacy RSA key-wrap path.
}
