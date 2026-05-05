import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

fun buildConfigValue(key: String): String {
    return (localProperties.getProperty(key) ?: project.findProperty(key) as String? ?: "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

val supabaseUrl = buildConfigValue("SUPABASE_URL")
val supabaseAnonKey = buildConfigValue("SUPABASE_ANON_KEY")

android {
    namespace = "com.gabby.studiowebwrapper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gabby.studiowebwrapper"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
        buildConfig = true
    }
    packagingOptions {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

// Copy root TorchScript weights into app assets so ModelRunner can load them at runtime.
tasks.register("copyWeightsToAssets") {
    doLast {
        val src = rootProject.file("weights.torchscript")
        if (!src.exists()) {
            println("No weights.torchscript found at project root; skipping copy.")
            return@doLast
        }
        val assetsDir = file("src/main/assets")
        if (!assetsDir.exists()) assetsDir.mkdirs()
        val dest = File(assetsDir, src.name)
        src.copyTo(dest, overwrite = true)
        println("Copied ${src.absolutePath} -> ${dest.absolutePath}")
        // Also copy quantized variant if present
        val qsrc = rootProject.file("weights_quant.torchscript")
        if (qsrc.exists()) {
            val qdest = File(assetsDir, qsrc.name)
            qsrc.copyTo(qdest, overwrite = true)
            println("Copied quantized ${qsrc.absolutePath} -> ${qdest.absolutePath}")
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn("copyWeightsToAssets")
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // PyTorch Mobile (full). If app size is a concern, switch to pytorch_android_lite.
    implementation("org.pytorch:pytorch_android:1.13.1")
    implementation("org.pytorch:pytorch_android_torchvision:1.13.1")

    // OpenCV for Android - prefer a local AAR or the extracted SDK module to avoid missing remote artifacts.
    // Option A: place a prebuilt AAR at `app/libs/opencv-4.5.5.aar`.
    // Option B: extract the OpenCV Android SDK next to the project (OpenCV-4.5.5-android-sdk/OpenCV-android-sdk)
    val opencvAar = file("libs/opencv-4.5.5.aar")
    val opencvsdkProjectDir = rootProject.file("OpenCV-4.5.5-android-sdk/OpenCV-android-sdk")
    if (opencvAar.exists()) {
        implementation(files(opencvAar))
    } else if (opencvsdkProjectDir.exists()) {
        println("Using included OpenCV SDK project at: ${opencvsdkProjectDir.path}")
        implementation(project(":opencv"))
    } else {
        // If you want to use a remote artifact instead, uncomment and update below.
        // Note: some OpenCV Android builds are not published to Maven Central.
        // implementation("org.opencv:opencv-android:4.5.5")
        println("WARNING: OpenCV not found. Add app/libs/opencv-4.5.5.aar or extract the SDK into OpenCV-4.5.5-android-sdk/OpenCV-android-sdk")
    }

    // Room (local DB)
    implementation("androidx.room:room-runtime:2.5.2")
    implementation("androidx.room:room-ktx:2.5.2")
    kapt("androidx.room:room-compiler:2.5.2")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")
}
