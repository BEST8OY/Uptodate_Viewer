pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
    configurations.all {
        resolutionStrategy {
            force("org.jetbrains.kotlin:compose-group-mapping:2.4.0")
        }
    }
}

rootProject.name = "UptodateViewer"
include(":app")
