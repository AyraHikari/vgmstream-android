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
    }
}

rootProject.name = "VGMStreamSample"
include(":app")
include(":vgmstream-core")
project(":vgmstream-core").projectDir = file("../vgmstream-core")
include(":vgmstream-media3")
project(":vgmstream-media3").projectDir = file("../vgmstream-media3")
