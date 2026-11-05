import java.security.MessageDigest

plugins { id("com.android.application") }
android {
    namespace = "dev.cinematv.ranksysprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.cinematv.ranksysprobe"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-probe"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug") // Isolated, synthetic QA APK only.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
}
dependencies {
    // Static target call graph needs these five artifacts; device/R8 tests verify the closure.
    listOf("org.ranksys:RankSys-diversity:0.4.3", "org.ranksys:RankSys-novdiv:0.4.3",
        "org.ranksys:RankSys-core:0.4.3", "it.unimi.dsi:fastutil:7.0.6", "org.jooq:jool:0.9.10")
        .forEach { implementation(it) { isTransitive = false } }
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
tasks.register("reportDependencies") {
    doLast {
        configurations.getByName("debugRuntimeClasspath").resolvedConfiguration.resolvedArtifacts.sortedBy { it.moduleVersion.id.toString() }.forEach {
            println("PROBE_DEP\t${it.moduleVersion.id}\t${it.file.length()}")
        }
    }
}

val sourceArtifacts by configurations.creating { isTransitive = false }
dependencies {
    sourceArtifacts("org.ranksys:RankSys-core:0.4.3:sources")
    sourceArtifacts("org.ranksys:RankSys-novdiv:0.4.3:sources")
    sourceArtifacts("org.ranksys:RankSys-diversity:0.4.3:sources")
}
tasks.register<Copy>("archiveCoreSources") {
    from(sourceArtifacts)
    into(rootDir.resolve("../../probe-evidence/upstream-sources"))
}

val stageCoreSources by tasks.registering(Copy::class) {
    from(sourceArtifacts)
    into(layout.buildDirectory.dir("generated/thirdPartyAssets/third-party/sources"))
    doFirst {
        val expected = mapOf(
            "RankSys-core-0.4.3-sources.jar" to "3a09660867d88ebbe1741dcb030e970403afe520d7da90fd4018d5ba0e65535d",
            "RankSys-diversity-0.4.3-sources.jar" to "6754607fb5d501c10d04e31268a40228970bf49a43777ed2d63beebac0da86bf",
            "RankSys-novdiv-0.4.3-sources.jar" to "36795aedfb0885f9f118f48fd9598be607989e5d8e5ea680490872112b68774d")
        check(sourceArtifacts.files.map { it.name }.toSet() == expected.keys)
        sourceArtifacts.files.forEach { file ->
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            check(hash == expected.getValue(file.name)) { "Unexpected published source bytes: ${file.name}" }
        }
    }
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/thirdPartyAssets"))
tasks.named("preBuild") { dependsOn(stageCoreSources) }

tasks.register<Copy>("archiveCoreBinaries") {
    from(configurations.getByName("debugRuntimeClasspath"))
    into(rootDir.resolve("../../probe-evidence/runtime-jars"))
}
