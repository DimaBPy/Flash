plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.flash"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.flash"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.animation)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // Material3 (pinned alpha — outside BOM)
    implementation(libs.compose.material3)

    // Navigation
    implementation(libs.navigation.compose)
    implementation(libs.activity.compose)

    // Lifecycle / ViewModel
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Ktor server
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.core)

    // Ktor client
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.serialization.json)

    // Images
    implementation(libs.coil.compose)

    // Persistence
    implementation(libs.datastore.preferences)

    // Liquid Glass
    implementation(libs.backdrop)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.register("printVersionName") {
    doLast { println(android.defaultConfig.versionName) }
}