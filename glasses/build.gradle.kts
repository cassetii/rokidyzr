plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.nala.rokidpdf.glasses"
    compileSdk = 34

    defaultConfig {
        applicationId = "id.nala.rokidpdf.glasses"
        minSdk = 28
        // targetSdk 30: pada Android 12 izin BLUETOOTH lama otomatis dipetakan ke
        // BLUETOOTH_CONNECT saat instal, jadi biasanya tanpa dialog di kacamata.
        // Bila firmware tetap menolak, aplikasi mengajukan izin runtime sendiri.
        targetSdk = 30
        versionCode = 16
        versionName = "1.1.0"
    }

    // Kunci tanda tangan TETAP (bukan acak per build) supaya versi baru bisa dipasang
    // di atas versi lama. Hanya untuk pemakaian pribadi/uji coba.
    signingConfigs {
        create("nala") {
            storeFile = rootProject.file("keystore/nala-debug.jks")
            storePassword = "nalarokid"
            keyAlias = "nala"
            keyPassword = "nalarokid"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("nala")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("nala")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Kode protokol yang sama dipakai HP dan kacamata
    sourceSets {
        getByName("main").java.srcDir("../common/src/main/kotlin")
    }
}

// Tidak ada dependensi eksternal: hanya API bawaan Android (Bluetooth + PdfRenderer).
