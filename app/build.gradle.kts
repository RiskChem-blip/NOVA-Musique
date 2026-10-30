plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.nova.musique"
    compileSdk = 34

    // Numéro de version = numéro de fabrication GitHub : chaque APK remplace le précédent
    val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

    defaultConfig {
        applicationId = "app.nova.musique"
        minSdk = 24
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
        // Clé YouTube : secret GitHub YOUTUBE_API_KEY, injecté au moment de fabriquer l'APK (jamais écrit dans le code)
        val ytKey = (System.getenv("YOUTUBE_API_KEY") ?: "").trim().replace("\\", "").replace("\"", "")
        buildConfigField("String", "YT_KEY", "\"$ytKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Toujours la même signature : les mises à jour s'installent par-dessus, sans rien perdre
    signingConfigs {
        create("novamusique") {
            storeFile = file("novamusique.keystore")
            storePassword = "novamusique2026"
            keyAlias = "novamusique"
            keyPassword = "novamusique2026"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("novamusique")
        }
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("com.google.guava:guava:33.2.1-android")
}
