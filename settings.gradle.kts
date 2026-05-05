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

rootProject.name = "StudioWebWrapper"
// If the OpenCV Android SDK is extracted in the workspace, include it as a project module.
val opencvsdk = file("OpenCV-4.5.5-android-sdk/OpenCV-android-sdk")
if (opencvsdk.exists()) {
    println("Including OpenCV SDK module from: ${opencvsdk.path}")
    include(":opencv")
    project(":opencv").projectDir = file(opencvsdk.path + "/sdk")
}

include(":app")
