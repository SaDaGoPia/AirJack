plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ace4.airplayreceiver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ace4.airplayreceiver"
        // Target device is a Samsung SM-G357FZ (Galaxy Ace 4) on Android 4.4.4 (API 19).
        // minSdk/targetSdk are pinned to that exact API level: this app is single-purpose
        // and never intended to run on newer devices, which sidesteps API 21+/26+/33+/34+
        // behavior changes (TXT record APIs, notification channels, foreground service
        // types) that don't apply on the real target anyway.
        minSdk = 19
        targetSdk = 19
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // Pure-Java mDNS/DNS-SD implementation used for _raop._tcp / _airplay._tcp
    // advertisement with full TXT record support. Android's built-in NsdManager
    // can't set TXT records until API 21, so it can't be used on this API 19 target.
    implementation("org.jmdns:jmdns:3.5.9")
}
