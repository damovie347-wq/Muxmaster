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
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    // ZORUNLU: ffmpeg-kit-full ve diğer kütüphaneler arasındaki
    // META-INF / .so çakışmalarını önler. Bu blok olmadan build FAIL olur.
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
        jniLibs {
            pickFirsts += "**/*.so"
        }
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

    // ⚠️ ÖNEMLİ: "com.arthenica:ffmpeg-kit-full:6.0-2" artık Maven Central'da YOK.
    // FFmpegKit projesi 6 Ocak 2025'te resmi olarak kapatıldı (retired) ve tüm
    // com.arthenica:ffmpeg-kit-* binary'leri 1 Nisan 2025'te Maven Central'dan
    // kaldırıldı (GitHub repo'su da arşivlendi). Orijinal koordinatla derleme
    // "Could not find com.arthenica:ffmpeg-kit-full:6.0-2" hatasıyla KESİN BAŞARISIZ olur.
    //
    // Aşağıda, aynı Java paket adını (com.arthenica.ffmpegkit.*) koruyan ve halen
    // Maven Central'da yayınlanan topluluk fork'u kullanılıyor — yani bu projedeki
    // FFmpegKit.java / FFprobeKit.java importları DEĞİŞMEDEN çalışır.
    // Güncel/alternatif bir fork bulursan sadece bu satırı değiştirmen yeterli.
    implementation("com.moizhassan.ffmpeg:ffmpeg-kit-16kb:6.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
