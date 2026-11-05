package dev.cinematv.data.recommendation

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

val recommendationLicenseFiles = listOf("THIRD-PARTY.txt", "MPL-2.0.txt", "Apache-2.0.txt")
val recommendationSourceFiles = listOf("core", "novdiv", "diversity").map { "sources/RankSys-$it-0.4.3-sources.jar" }

/** Fixed public software assets only: callers cannot add a profile, history or private file path. */
fun writeRecommendationSourceArchive(output: OutputStream, openPublicAsset: (String) -> InputStream) {
    ZipOutputStream(output).use { zip ->
        (recommendationLicenseFiles + recommendationSourceFiles).forEach { name ->
            zip.putNextEntry(ZipEntry(name))
            openPublicAsset("recommendation-third-party/$name").use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}
