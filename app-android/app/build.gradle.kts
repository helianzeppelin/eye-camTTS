plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Senha do Wi-Fi do óculos, lida do segredos.h do firmware (fora do Git) para a
// conexão automática. Ela vai dentro do APK: para um APK público, compile com
// -PsemSenha, e a conexão passa a ser manual.
val segredosFirmware = layout.projectDirectory.file("../../firmware/eyecam_firmware/segredos.h")
val senhaWifiOculos: String = if (providers.gradleProperty("semSenha").isPresent) "" else
    providers.fileContents(segredosFirmware).asText.orNull
    ?.let { Regex("""#define\s+EYECAM_AP_SENHA\s+"(.*)"\s*$""", RegexOption.MULTILINE).find(it) }
    ?.groupValues?.get(1)
    ?.replace(Regex("""\\(.)"""), "$1")   // desfaz os escapes do C (\" e \\)
    ?: ""

android {
    namespace = "com.eyecam.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.eyecam.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Escapes do Java: o valor vira código-fonte em BuildConfig.java
        val senhaEscapada = senhaWifiOculos.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "OCULOS_SENHA", "\"$senhaEscapada\"")

        // APK público (-PsemSenha): só bibliotecas para celulares ARM, para ficar menor
        if (providers.gradleProperty("semSenha").isPresent) {
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.opencv)
}