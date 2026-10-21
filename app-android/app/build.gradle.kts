plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Senha do Wi-Fi do óculos, lida do MESMO arquivo que o firmware usa
// (firmware/eyecam_firmware/segredos.h, fora do Git). Com ela o app pede a
// rede do óculos ao Android sozinho (ConexaoOculos.kt). Um arquivo só evita
// que firmware e app fiquem com senhas diferentes, e a senha continua fora
// do repositório público. Sem o arquivo, a senha fica vazia e o app volta a
// depender da conexão manual nas configurações de Wi-Fi.
// Atenção: a senha vai dentro do APK. Não distribua o APK compilado.
val segredosFirmware = layout.projectDirectory.file("../../firmware/eyecam_firmware/segredos.h")
val senhaWifiOculos: String = providers.fileContents(segredosFirmware).asText.orNull
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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Escapes do Java: o valor vira código-fonte em BuildConfig.java
        val senhaEscapada = senhaWifiOculos.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "OCULOS_SENHA", "\"$senhaEscapada\"")
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
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.opencv)
}