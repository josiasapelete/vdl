import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")     // captures d'écran des interfaces, sans téléphone (tests uniquement)
}

// Version : la même que l'application de bureau (vdl/__init__.py)
val vdlVersion: String = Regex("""__version__ = "([^"]+)"""")
    .find(rootProject.file("../vdl/__init__.py").readText())!!.groupValues[1]
val vdlVersionCode: Int = vdlVersion.split(".").map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }

// Signature de publication : variables d'environnement (CI) ou packaging/android/keystore.properties (local).
// Sans clé, l'APK de publication est signé avec la clé de debug (pratique pour tester, pas pour distribuer).
val keystoreProps = Properties().apply {
    val f = rootProject.file("../packaging/android/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? = System.getenv(env) ?: keystoreProps.getProperty(prop)

android {
    namespace = "app.vdl"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.vdl"
        minSdk = 24
        targetSdk = 35
        versionCode = vdlVersionCode
        versionName = vdlVersion
        // Dépôt GitHub qui publie les versions, pour signaler les mises à jour de VDL (vide = désactivé)
        buildConfigField("String", "UPDATE_REPO", "\"${System.getenv("VDL_UPDATE_REPO") ?: "josiasapelete/vdl"}\"")
    }

    signingConfigs {
        val storeFile = signingValue("VDL_KEYSTORE", "storeFile")
        if (storeFile != null) {
            create("release") {
                this.storeFile = rootProject.file(storeFile).takeIf { it.exists() } ?: file(storeFile)
                storePassword = signingValue("VDL_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("VDL_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("VDL_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // yt-dlp tourne dans un interpréteur Python embarqué : la réduction de code n'apporte rien
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // Un APK par architecture (plus léger) + un APK universel
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        // Python, ffmpeg et aria2c sont exécutés depuis le dossier des bibliothèques natives
        jniLibs.useLegacyPackaging = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    val ytdl = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
