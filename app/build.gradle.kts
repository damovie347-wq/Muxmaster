plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.muxmaster"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.muxmaster"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
    release {
        signingConfig = signingConfigs.getByName("debug")
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
}

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
    jvmTarget = "17"
    freeCompilerArgs += "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
}

    buildFeatures { compose = true }

    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
        jniLibs { pickFirsts += "**/*.so" }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.moizhassan.ffmpeg:ffmpeg-kit-16kb:6.1.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
