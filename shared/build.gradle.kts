plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    id("jacoco")
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

        withHostTest {
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kzstd)
                implementation(libs.ksoup)
                implementation(libs.sqlite.bundled)
                implementation(libs.room3.runtime)
                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(libs.koin.compose.viewmodel)
                implementation(libs.lifecycle.viewmodel)
                implementation(libs.lifecycle.viewmodel.savedstate)
                implementation(libs.navigation3.runtime)
                implementation(libs.navigation3.ui)
                implementation(libs.lifecycle.viewmodel.navigation3)
                implementation(libs.compose.material3)
                implementation(libs.compose.ui)
                implementation(libs.compose.material.icons.extended)

                // Koog AI agents
                implementation(libs.koog.agents)
                implementation(libs.koog.agents.tools)
                implementation(libs.koog.agents.features.event.handler)
                implementation(libs.koog.agents.features.memory)
                implementation(libs.koog.agents.features.trace)
                implementation(libs.koog.prompt.executor.openai.client)
                implementation(libs.koog.prompt.executor.anthropic.client)
                implementation(libs.koog.prompt.executor.google.client)
                implementation(libs.koog.prompt.executor.mistralai.client)
                implementation(libs.koog.prompt.executor.openrouter.client)
                implementation(libs.koog.prompt.executor.ollama.client)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.junit)
                implementation(libs.mockk)
                implementation(libs.coroutines.test)
                implementation(libs.koog.agents.test)
            }
        }

        androidMain {
            dependencies {
                implementation(libs.core.ktx)
                implementation(libs.webkit)
                implementation(libs.activity.compose)
                implementation(libs.koog.http.client.okhttp)
                implementation(libs.koog.utils.jvm)
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(libs.koog.http.client.okhttp)
                implementation(libs.koog.utils.jvm)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }

        val androidHostTest by getting {
            dependencies {
            }
        }
    }
}

configurations.configureEach {
    exclude(group = "ai.koog", module = "utils-android")
}

dependencies {
    add("kspDesktop", libs.room3.compiler)
}

tasks.register<JacocoReport>("jacocoDesktopTestReport") {
    dependsOn(tasks.named("desktopTest"))
    group = "Reporting"
    description = "Generate Jacoco coverage reports for Desktop tests."

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    val mainClasses = fileTree(layout.buildDirectory.dir("classes/kotlin/desktop/main")) {
        exclude(
            "**/AppDatabase_Impl*",
            "**/AppDatabaseConstructor*",
            "**/*Dao_Impl*",
            "**/*\$serializer*",
            "**/*_Factory*",
            "**/*_MembersInjector*"
        )
    }

    classDirectories.setFrom(mainClasses)
    sourceDirectories.setFrom(
        files(
            "src/commonMain/kotlin",
            "src/desktopMain/kotlin"
        )
    )
    executionData.setFrom(
        layout.buildDirectory.file("jacoco/desktopTest.exec")
    )
}
