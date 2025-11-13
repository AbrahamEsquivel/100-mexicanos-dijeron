plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt")
}

android {
    namespace = "com.example.cienmexicanosdijeron"

    // --- ¡¡CAMBIO 1!! ---
    // Bajamos a la versión estable (Android 14)
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.cienmexicanosdijeron"
        minSdk = 29

        // --- ¡¡CAMBIO 2!! ---
        targetSdk = 34

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

    // --- ¡¡CAMBIO 3!! ---
    // Bajamos a Java 8 (el más compatible)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {

    // --- DEPENDENCIAS BASE (Puestas a mano) ---
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.0")

    // --- GLIDE (Para GIFs) ---
    implementation("com.github.bumptech.glide:glide:4.15.1")
    kapt("com.github.bumptech.glide:compiler:4.15.1")

    // --- NAVIGATION
    val navVersion = "2.7.7"
    implementation("androidx.navigation:navigation-fragment-ktx:$navVersion")
    implementation("androidx.navigation:navigation-ui-ktx:$navVersion")
    implementation("com.github.imangazalievm:circlemenu:3.0.0")

    // --- Base de datos
    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version") // Para Coroutines
    kapt("androidx.room:room-compiler:$room_version")

    // --- TEST (Estas sí pueden usar libs) ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}