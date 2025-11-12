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

    // Firebase Firestore
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore.ktx)

    // Futures para ProcessCameraProvider
    implementation(libs.guava.listenablefuture)
    implementation(libs.androidx.concurrent.futures)

    // Test
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Guava completo (variant ANDROID) => trae ListenableFuture
    implementation("com.google.guava:guava:32.1.3-android")
// Futures de AndroidX (CameraX lo usa)
    implementation("androidx.concurrent:concurrent-futures:1.1.0")

}
