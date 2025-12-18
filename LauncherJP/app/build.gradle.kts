plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Removido: alias(libs.plugins.kotlin.compose)  // Não usado no código
}

android {
    namespace = "br.tv.hallo.jptvmais.launcher"
    compileSdk = 34  // Baixado para 34 para estabilidade; atualize se necessário

    defaultConfig {
        applicationId = "br.tv.hallo.jptvmais.launcher"
        minSdk = 24
        targetSdk = 34  // Ajustado para matching compileSdk
        versionCode = 12
        versionName = "1.2"
        vectorDrawables {
            useSupportLibrary = true
        }
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = false  // Desabilitado
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.14")  // Atualizado para versão estável recente
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6") // Para lifecycleScope
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1") // Para coroutines
    // Removidas todas as deps de Compose, já que não usadas
}