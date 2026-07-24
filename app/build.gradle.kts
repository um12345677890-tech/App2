plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.stocktracker.app"
    compileSdk = 35

    // Numéro de version = numéro du run CI (incrémenté à chaque build GitHub
    // Actions) pour qu'Android reconnaisse chaque APK comme une mise à jour.
    val ciVersionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1

    defaultConfig {
        applicationId = "com.stocktracker.app"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = "1.0.$ciVersionCode"
    }

    // Clé de signature fixe commitée dans le dépôt : tous les APK partagent la
    // même signature, donc les mises à jour s'installent SANS désinstaller.
    signingConfigs {
        create("shared") {
            storeFile = file("keystore.jks")
            storePassword = "stocktracker"
            keyAlias = "stocktracker"
            keyPassword = "stocktracker"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            signingConfig = signingConfigs.getByName("shared")
            isMinifyEnabled = true
            isShrinkResources = true
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
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
