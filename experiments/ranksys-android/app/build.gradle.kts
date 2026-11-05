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

val sourceArtifacts by configurations.creating
dependencies {
    sourceArtifacts("org.ranksys:RankSys-core:0.4.3:sources")
    sourceArtifacts("org.ranksys:RankSys-novdiv:0.4.3:sources")
    sourceArtifacts("org.ranksys:RankSys-diversity:0.4.3:sources")
}
tasks.register<Copy>("archiveCoreSources") {
    from(sourceArtifacts)
    into(rootDir.resolve("../../probe-evidence/upstream-sources"))
}
