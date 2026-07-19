plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.room3)
}

kotlin {
    androidLibrary {
        namespace = "com.clinref.shared"
        compileSdk = 37

        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }

        androidResources {
            enable = true
        }
    }

    sourceSets {
        androidMain.dependencies {
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

            // Hilt
            implementation(libs.hilt.android)
            ksp(libs.hilt.compiler)
            implementation(libs.hilt.navigation.compose)

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
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}
