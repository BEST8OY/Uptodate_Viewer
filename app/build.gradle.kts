plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.room3)
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

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:compose-group-mapping:2.4.10")
        force("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:${libs.versions.kotlin.get()}")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${libs.versions.kotlin.get()}")
    }
}

dependencies {
    // Compose BOM — manages versions for all Compose artifacts
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Core
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)

    // Navigation 3
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.lifecycle.viewmodel.navigation3)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation("com.google.errorprone:error_prone_annotations:2.50.0")

    // DataStore
    implementation(libs.datastore.preferences)

    // WebView
    implementation(libs.webkit)

    // JSON
    implementation(libs.kotlinx.serialization.json)

    // Koog AI agents (modular — each feature is a separate artifact)
    implementation(libs.koog.agents)
    implementation(libs.koog.agents.tools)
    implementation(libs.koog.agents.features.event.handler)
    implementation(libs.koog.prompt.executor.openai.client)
    implementation(libs.koog.prompt.executor.anthropic.client)
    implementation(libs.koog.prompt.executor.google.client)
    implementation(libs.koog.prompt.executor.ollama.client)
    // llms-all at 1.0.0-beta (no stable 1.0.0 published yet)
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
