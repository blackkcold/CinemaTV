import java.security.MessageDigest
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.compose")
}

apply(from = "noto-fonts.gradle")

// Keep AGP wiring in the plugin-owning script; the downloader stays plugin-independent.
tasks.named("preBuild") { dependsOn("prepareNotoFonts") }

// Channels share package/signing identity so switching is an ordinary forward upgrade.
// Properties avoid renaming existing Debug/Release tasks or preview artifact paths.
val updateChannel = providers.gradleProperty("cinematvChannel").getOrElse("dev")
require(updateChannel in setOf("stable", "dev")) { "cinematvChannel must be stable or dev" }
// Personal-account experiments stay on their dev branch. Refuse mislabelled stable builds
// even if someone accidentally merges or builds that branch with the stable property.
if (updateChannel == "stable") {
    val devOnlyAccountSources = listOf("src/main/java/dev/cinematv/data/douban", "src/main/java/dev/cinematv/ui/douban")
    require(devOnlyAccountSources.none { path -> file(path).walkTopDown().any { it.isFile && it.extension == "kt" } }) {
        "Unaccepted Douban personal-account sources may only be built for dev, never stable"
    }
}
val updatesEnabled = providers.gradleProperty("cinematvUpdatesEnabled").getOrElse("false").toBooleanStrict()
// CI prepares public unsigned bytes. Only the authorized external signer may finish them.
val unsignedCandidate = providers.gradleProperty("cinematvUnsignedCandidate").getOrElse("false").toBooleanStrict()
val externalSigningNames = listOf("CINEMATV_KEYSTORE_PATH", "CINEMATV_KEYSTORE_PASSWORD", "CINEMATV_KEY_ALIAS", "CINEMATV_KEY_PASSWORD")
require(System.getenv("GITHUB_ACTIONS") != "true" || externalSigningNames.none { !System.getenv(it).isNullOrBlank() }) {
    "Private production signing credentials must never be configured in GitHub Actions"
}
if (unsignedCandidate) {
    require(externalSigningNames.none { !System.getenv(it).isNullOrBlank() }) {
        "Unsigned candidates must not receive signing credentials"
    }
}
if (updatesEnabled) {
    require(unsignedCandidate || externalSigningNames.all { !System.getenv(it).isNullOrBlank() }) {
        "Updates require external signing or an explicitly unsigned candidate for external signing"
    }
    require(providers.gradleProperty("cinematvVersionCode").isPresent && providers.gradleProperty("cinematvVersionName").isPresent) {
        "Update-enabled builds require explicit globally increasing version information"
    }
}

android {
    namespace = "dev.cinematv"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.cinematv"
        minSdk = 26
        targetSdk = 35
        // Preserve literal preview defaults for the existing immutable provenance reader.
        versionCode = 9
        versionName = "0.8.0-preview"
        providers.gradleProperty("cinematvVersionCode").orNull?.let { versionCode = it.toInt() }
        providers.gradleProperty("cinematvVersionName").orNull?.let { versionName = it }
        require(versionCode!! in 1..2_100_000_000)
        buildConfigField("String", "UPDATE_CHANNEL", "\"$updateChannel\"")
        buildConfigField("boolean", "UPDATES_ENABLED", updatesEnabled.toString())
        manifestPlaceholders["updateChannel"] = updateChannel
        manifestPlaceholders["channelIcon"] = if (updateChannel == "dev") "@mipmap/ic_launcher_dev" else "@mipmap/ic_launcher"
        manifestPlaceholders["channelRoundIcon"] = if (updateChannel == "dev") "@mipmap/ic_launcher_dev" else "@mipmap/ic_launcher_round"
        manifestPlaceholders["channelLabel"] = if (updateChannel == "dev") "CinemaTV Dev" else "CinemaTV"
        manifestPlaceholders["channelBanner"] = if (updateChannel == "dev") "@drawable/tv_banner_dev" else "@drawable/tv_banner"
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
        // Isolated performance host. It inherits production optimizations but is
        // debug-signed for cloud installation, never shipped as a release artifact.
        create("glassBenchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            applicationIdSuffix = ".glassbenchmark"
            buildConfigField("boolean", "UPDATES_ENABLED", "false")
            matchingFallbacks += listOf("release")
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
    sourceSets["glassBenchmark"].res.srcDir("src/debug/res")
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kapt {
    arguments { arg("room.schemaLocation", "$projectDir/schemas") }
}

dependencies {
    implementation(project(":backdrop"))
    // Published, unmodified RankSys core. Explicit closure avoids unrelated algorithms.
    listOf("org.ranksys:RankSys-diversity:0.4.3", "org.ranksys:RankSys-novdiv:0.4.3",
        "org.ranksys:RankSys-core:0.4.3", "it.unimi.dsi:fastutil:7.0.6", "org.jooq:jool:0.9.10")
        .forEach { implementation(it) { isTransitive = false } }
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
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
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
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

val verifyRecommendationCore by tasks.registering {
    doLast {
        val expected = mapOf(
            "RankSys-core-0.4.3.jar" to "11a69497cb46c17bbbb66c7c5b8bc077557ba3142993816e3b954291ceb2044c",
            "RankSys-diversity-0.4.3.jar" to "c5308d43a11faeb879c95a280430106d8c9ef0024f1e621abfe35e4e805c9936",
            "RankSys-novdiv-0.4.3.jar" to "27f9682b4939739814c982ac567073cee6c676c36069e3c8aa11f03637133154",
            "fastutil-7.0.6.jar" to "f723d392658f62b3fbed7357cc7d39b10484836e2cbc92dc485b2121e86c4ad9",
            "jool-0.9.10.jar" to "c71cd5a5a0ddb88973bab02673bcf7ee21a8e2ebf4c9efdc12b47156ef1b4a9e")
        // Select original external JARs before artifact resolution. Resolving every
        // artifact first is ambiguous for Android project libraries with multiple
        // runtime variants (classes, resources, manifest, assets, and more).
        val artifacts = configurations.getByName("debugRuntimeClasspath").incoming.artifactView {
            componentFilter { id ->
                id is ModuleComponentIdentifier && (id.group == "org.ranksys" ||
                    (id.group == "it.unimi.dsi" && id.module == "fastutil") ||
                    (id.group == "org.jooq" && id.module == "jool"))
            }
            attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE)
        }.artifacts.artifacts
        check(artifacts.map { it.file.name }.toSet() == expected.keys) { "Unexpected recommendation core dependency graph" }
        artifacts.forEach { artifact ->
            val hash = MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes()).joinToString("") { "%02x".format(it) }
            check(hash == expected.getValue(artifact.file.name)) { "Unexpected core artifact bytes: ${artifact.file.name}" }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyRecommendationCore) }
