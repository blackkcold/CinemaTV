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
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
}
dependencies {
    implementation("org.ranksys:RankSys-diversity:0.4.3")
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
