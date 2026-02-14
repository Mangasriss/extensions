plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinSerialization)
}

android {
    namespace = "eu.kanade.tachiyomi.extension.fr.mangamoins"
    compileSdk = 34

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.fr.mangamoins"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "${libs.versions.extlib.get().substringBeforeLast('.')}.$versionCode"
        base {
            archivesName = "tachiyomi-fr.mangamoins-v$versionName"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("signingkey.jks")
            storePassword = System.getenv("KEY_STORE_PASSWORD")
            keyAlias = System.getenv("ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    dependenciesInfo {
        includeInApk = false
    }

    buildFeatures {
        resValues = false
        shaders = false
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_1_8.toString()
    }
}

dependencies {
    compileOnly(libs.tachiyomi.lib)
    compileOnly(libs.okhttp)
    compileOnly(libs.kotlinx.serialization.json)
    compileOnly(libs.rxjava)
    compileOnly(libs.jsoup)
}
