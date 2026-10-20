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
rootProject.name = "Coursatrix"
include(":core")
// Allows domain tests and Wrapper generation without an installed Android SDK.
if (!providers.gradleProperty("coreOnly").isPresent) include(":app")
