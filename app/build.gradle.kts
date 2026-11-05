import java.security.MessageDigest

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
        versionCode = 7
        versionName = "0.6.0-preview"
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
    // Published, unmodified RankSys core. Explicit closure avoids unrelated algorithms.
    listOf("org.ranksys:RankSys-diversity:0.4.3", "org.ranksys:RankSys-novdiv:0.4.3",
        "org.ranksys:RankSys-core:0.4.3", "it.unimi.dsi:fastutil:7.0.6", "org.jooq:jool:0.9.10")
        .forEach { implementation(it) { isTransitive = false } }
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

// MPL source delivery is part of the APK, not a link to an expiring CI artifact.
val recommendationSources by configurations.creating { isTransitive = false }
dependencies {
    listOf("core", "novdiv", "diversity").forEach {
        recommendationSources("org.ranksys:RankSys-$it:0.4.3:sources")
    }
}
val stageRecommendationSources by tasks.registering(Copy::class) {
    from(recommendationSources)
    into(layout.buildDirectory.dir("generated/recommendationAssets/recommendation-third-party/sources"))
    doFirst {
        val expected = mapOf(
            "RankSys-core-0.4.3-sources.jar" to "3a09660867d88ebbe1741dcb030e970403afe520d7da90fd4018d5ba0e65535d",
            "RankSys-diversity-0.4.3-sources.jar" to "6754607fb5d501c10d04e31268a40228970bf49a43777ed2d63beebac0da86bf",
            "RankSys-novdiv-0.4.3-sources.jar" to "36795aedfb0885f9f118f48fd9598be607989e5d8e5ea680490872112b68774d")
        check(recommendationSources.files.map { it.name }.toSet() == expected.keys)
        recommendationSources.files.forEach { file ->
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            check(hash == expected.getValue(file.name)) { "Unexpected RankSys source: ${file.name}" }
        }
    }
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/recommendationAssets"))
tasks.named("preBuild") { dependsOn(stageRecommendationSources) }
