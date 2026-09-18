plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.nala.rokidpdf.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "id.nala.rokidpdf.phone"
        minSdk = 28
        targetSdk = 34
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
