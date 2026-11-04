import groovy.json.JsonSlurper
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

// Build-time only. The APK contains all fonts; application runtime never needs a font server.
val notoLock = rootProject.file("scripts/fonts/noto-fonts.lock.json")
val notoAssets = layout.buildDirectory.dir("generated/notoAssets")
val prepareNotoFonts by tasks.registering {
    inputs.file(notoLock)
    outputs.dir(notoAssets)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val lock = JsonSlurper().parse(notoLock) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val fonts = lock["fonts"] as List<Map<String, Any>>
        val directory = notoAssets.get().dir("fonts").asFile.apply { mkdirs() }
        fun digest(file: File): String {
            val sha = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val bytes = ByteArray(65536)
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    sha.update(bytes, 0, count)
                }
            }
            return sha.digest().joinToString("") { "%02x".format(it) }
        }
        for (font in fonts) {
            val name = font["name"] as String
            require(name.matches(Regex("[A-Za-z0-9-]+\\.(ttf|ttc)")))
            val expectedSize = (font["size"] as Number).toLong()
            val expectedHash = font["sha256"] as String
            val target = File(directory, name)
            if (target.isFile && target.length() == expectedSize && digest(target) == expectedHash) continue
            check(!gradle.startParameter.isOffline) {
                "Missing or invalid pinned Noto asset $name. Run prepareNotoFonts once online before --offline."
            }
            val url = URI(font["url"] as String).toURL()
            require(url.protocol == "https" && url.host == "raw.githubusercontent.com")
            val temporary = File(directory, "$name.part")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
            val deadline = System.nanoTime() + 120_000_000_000L
            try {
                check(connection.responseCode == 200) { "Noto font download failed: HTTP ${connection.responseCode} ($name)" }
                connection.inputStream.buffered().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val bytes = ByteArray(65536)
                        var total = 0L
                        while (true) {
                            check(System.nanoTime() < deadline) { "Noto font download deadline exceeded: $name" }
                            val count = input.read(bytes)
                            if (count < 0) break
                            total += count
                            check(total <= expectedSize) { "Oversized Noto asset: $name" }
                            output.write(bytes, 0, count)
                        }
                    }
                }
                check(temporary.length() == expectedSize && digest(temporary) == expectedHash) {
                    "Noto asset integrity failure: $name"
                }
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                connection.disconnect()
                temporary.delete()
            }
        }
    }
}

// Explicit dependency covers all APK variants; avoid AGP-internal generated source APIs.
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareNotoFonts) }
extensions.configure<com.android.build.gradle.AppExtension> {
    sourceSets.getByName("main").assets.srcDir(notoAssets)
}
