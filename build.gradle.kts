plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// Optional ASCII output path for Windows JDK argument-file encoding issues.
allprojects {
    providers.gradleProperty("buildRoot").orNull?.let { root ->
        layout.buildDirectory.set(file("$root/${project.name}"))
    }
}
