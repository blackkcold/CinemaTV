package dev.cinematv.data.recommendation

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

class RecommendationSourceArchiveTest {
    @Test fun exportsOnlySixFixedPublicAssetsAndPreservesBytes() {
        val requested = mutableListOf<String>()
        val output = ByteArrayOutputStream()
        writeRecommendationSourceArchive(output) { name -> requested += name; ByteArrayInputStream(name.toByteArray()) }
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = String(zip.readBytes())
            }
        }
        assertEquals(recommendationLicenseFiles + recommendationSourceFiles, entries.keys.toList())
        assertEquals(6, entries.size)
        assertEquals(entries.keys.map { "recommendation-third-party/$it" }, requested)
        entries.forEach { (name, bytes) -> assertEquals("recommendation-third-party/$name", bytes) }
    }
    @Test fun missingRequiredSourceFailsAndClosesOutput() {
        var closed = false
        val output = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
        try {
            writeRecommendationSourceArchive(output) { throw IOException("synthetic missing required asset") }
            fail("Missing source cannot be reported as a successful export")
        } catch (_: IOException) { assertTrue(closed) }
    }
}
