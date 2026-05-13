plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.google.services) // google-services.json
}

android {
    namespace = "com.example.shadowrec"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.shadowrec"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ML Kit (QR)
    implementation(libs.mlkit.barcode)

    // Play Services Location (GPS)
    implementation(libs.play.services.location)

    // Firebase (YA LO TENÍAS)
    implementation(platform(libs.firebase.bom))      // BOM
    implementation(libs.firebase.firestore.ktx)     // Firestore
    implementation(libs.firebase.auth.ktx) // 🔥 NUEVO: Authgit

    // Futures + Guava para ListenableFuture
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.guava.android)

    // Retrofit + Gson
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Biometría para validaciones
    implementation("androidx.biometric:biometric:1.1.0")
}


