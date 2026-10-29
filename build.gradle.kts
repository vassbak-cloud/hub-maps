plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

val buildNo = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "gr.vasili.hudmaps"
    compileSdk = 34

    defaultConfig {
        applicationId = "gr.vasili.hudmaps"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNo
        versionName = "1.0.$buildNo"
    }

    // Σταθερό κλειδί υπογραφής, ώστε κάθε νέα έκδοση να εγκαθίσταται πάνω στην προηγούμενη
    signingConfigs {
        create("hud") {
            storeFile = file("hud.keystore")
            storePassword = "hudmaps123"
            keyAlias = "hud"
            keyPassword = "hudmaps123"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("hud")
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.srcDirs("src")
            res.srcDirs("res")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
