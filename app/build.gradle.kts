plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signature : fournie par le workflow GitHub Actions (clé fixe stockée en secret).
// Sans ces variables, le build release sort non signé.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
val keyAliasName: String = System.getenv("KEY_ALIAS") ?: "suivinutri"

// versionCode croissant à chaque build GitHub, pour que les mises à jour s'installent par-dessus.
// Le nom de version vient du workflow (VERSION_NAME), seule source du numéro.
val buildNumber: Int = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1
val versionNameEnv: String = System.getenv("VERSION_NAME") ?: "dev.$buildNumber"

android {
    namespace = "io.github.dash52000.suivinutri"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.dash52000.suivinutri"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = versionNameEnv
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else null
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
        buildConfig = true
    }

    lint {
        // Appli perso hors Play Store : un avertissement lint ne doit pas bloquer l'APK.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.health.connect:connect-client:1.1.0")
}
