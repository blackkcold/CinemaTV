plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.kyant.backdrop"
    compileSdk = 36
    // Optical entry points are API31-gated; API26–30 retain the app's static material.
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
}
kotlin { jvmToolchain(17); compilerOptions { freeCompilerArgs.add("-Xcontext-parameters") } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    testImplementation("junit:junit:4.13.2")
}
