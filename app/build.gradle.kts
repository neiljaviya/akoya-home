plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.akoya.home"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.akoya.home"
        minSdk = 28
        targetSdk = 35
        versionCode = 8
        versionName = "1.6.0"
    }

    // Fixed key committed with the project so every build (Android Studio or CI)
    // can be installed as an update over the previous one. Personal sideload use only.
    signingConfigs {
        create("akoya") {
            storeFile = file("akoya-home.keystore")
            storePassword = "akoyahome"
            keyAlias = "akoya"
            keyPassword = "akoyahome"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("akoya")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("akoya")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
    // Live TV playback (HLS and MPEG-TS streams)
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    testImplementation("junit:junit:4.13.2")
}
