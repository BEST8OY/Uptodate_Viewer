plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    android {
        namespace = "com.clinref.shared"
        compileSdk = 37
        minSdk = 35

        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }

        androidResources {
            enable = true
        }
    }
}
