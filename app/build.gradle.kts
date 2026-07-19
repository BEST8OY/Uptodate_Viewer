plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.clinref.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.clinref.app"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
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

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":shared"))

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    compileOnly(libs.error.prone.annotations)

    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)

    // Core
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)

    // Navigation 3
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.lifecycle.viewmodel.navigation3)

    // DataStore
    implementation(libs.datastore.preferences)

    // WebView
    implementation(libs.webkit)

    // JSON
    implementation(libs.kotlinx.serialization.json)

    // Koog AI agents
    implementation(libs.koog.agents)
    implementation(libs.koog.agents.tools)
    implementation(libs.koog.agents.features.event.handler)
    implementation(libs.koog.prompt.executor.openai.client)
    implementation(libs.koog.prompt.executor.anthropic.client)
    implementation(libs.koog.prompt.executor.google.client)
    implementation(libs.koog.prompt.executor.ollama.client)
    implementation(libs.koog.prompt.executor.llms.all)
    implementation(libs.koog.http.client.okhttp)

    // Room database (3.0)
    implementation(libs.room3.runtime)
    ksp(libs.room3.compiler)
    implementation(libs.sqlite.bundled)

    // Security (encrypted key storage)
    implementation(libs.security.crypto)

    // Markdown rendering in Compose
    implementation(libs.multiplatform.markdown.renderer)
    implementation(libs.multiplatform.markdown.renderer.m3)
}

// Koog utils-jvm has Coroutines_jvmKt which http-client-okhttp references.
// utils-android has Coroutines_androidKt instead — they're platform-specific, not duplicates.
// But base classes (CloseableKt, StringExtensionsKt, etc.) are identical in both,
// causing duplicate class errors. Must use utils-jvm and exclude utils-android.
configurations.configureEach {
    exclude(group = "ai.koog", module = "utils-android")
}
