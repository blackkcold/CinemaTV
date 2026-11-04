plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.compose")
}

apply(from = "noto-fonts.gradle")

// Keep AGP wiring in the plugin-owning script; the downloader stays plugin-independent.
tasks.named("preBuild") { dependsOn("prepareNotoFonts") }

android {
    namespace = "dev.cinematv"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.cinematv"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.5.0-preview"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (!System.getenv("CINEMATV_KEYSTORE_PATH").isNullOrBlank()) {
            create("production") {
                storeFile = file(System.getenv("CINEMATV_KEYSTORE_PATH")!!)
                storePassword = System.getenv("CINEMATV_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CINEMATV_KEY_ALIAS")
                keyPassword = System.getenv("CINEMATV_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("production")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    // Keep fonts mmap-eligible; avoid inflating a ~19 MB collection for asset buffer access.
    androidResources { noCompress += listOf("ttc", "ttf") }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/notoAssets"))
    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kapt {
    arguments { arg("room.schemaLocation", "$projectDir/schemas") }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    // Explicit API use for cancellable JSON requests; matches Coil 3.3.0's existing transport.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    val room = "2.8.4"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    kapt("androidx.room:room-compiler:$room")
    androidTestImplementation("androidx.room:room-testing:$room")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
