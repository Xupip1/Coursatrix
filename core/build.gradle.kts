import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin.compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
dependencies {
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    // Desktop PDFBox only to read the private PDF sample in tests; the app uses pdfbox-android.
    testImplementation(libs.pdfbox.desktop)
}
tasks.test { useJUnit() }
