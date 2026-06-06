pluginManagement {
    plugins {
        id("com.android.library") version "8.12.0"
        id("org.jetbrains.kotlin.android") version "2.2.0"
    }

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
    }
}

rootProject.name = "VGMStreamAndroidLibrary"
