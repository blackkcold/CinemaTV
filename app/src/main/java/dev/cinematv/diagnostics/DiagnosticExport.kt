package dev.cinematv.diagnostics

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.cinematv.BuildConfig
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Creates a user-initiated, local-only archive. Nothing is uploaded automatically. */
object DiagnosticExport {
    /** One export at a time: a second tap must not write the same archive or trim a live one. */
    private val createLock = Any()

    fun create(context: Context, logs: List<File>): File? = synchronized(createLock) {
        runCatching {
            val dir = context.getExternalFilesDir("diagnostics") ?: File(context.filesDir, "diagnostics")
            if (!dir.isDirectory && !dir.mkdirs()) return null
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
                .withZone(ZoneId.systemDefault()).format(Instant.now())
            var target = File(dir, "cinematv-diag-$stamp.zip")
            var index = 2
            while (target.exists()) {
                target = File(dir, "cinematv-diag-$stamp-$index.zip")
                index++
            }
            try {
                val manifest = manifest(context, logs).toByteArray(Charsets.UTF_8)
                ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(manifest)
                    zip.closeEntry()
                    logs.forEach { file ->
                        zip.putNextEntry(ZipEntry("logs/${file.name}"))
                        file.inputStream().use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                target
            } catch (error: Exception) {
                runCatching { target.delete() }
                throw error
            }
        }.getOrNull()?.also { trim(it.parentFile ?: return@also) }
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "CinemaTV 诊断日志")
            clipData = android.content.ClipData.newRawUri("diagnostics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun manifest(context: Context, logs: List<File>): String = JSONObject()
        .put("app", "CinemaTV")
        .put("version", BuildConfig.VERSION_NAME)
        .put("channel", BuildConfig.UPDATE_CHANNEL)
        .put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
        .put("sdk", android.os.Build.VERSION.SDK_INT)
        .put("exportedAt", Instant.now().toString())
        .put("level", DiagnosticRedaction.levelName(DiagnosticLog.level))
        .put("files", logs.size)
        .put("bytes", logs.sumOf { it.length() })
        .put("note", "本地诊断日志；不含 Cookie、Token、账号标识、片名或原始页面。")
        .toString(2)

    /** Keep the newest exports and cap total size; logging archives are not user data. */
    private fun trim(dir: File) {
        val archives = dir.listFiles { file ->
            file.isFile && file.name.startsWith("cinematv-diag-") && file.name.endsWith(".zip")
        }?.sortedByDescending { it.lastModified() } ?: return
        var total = archives.sumOf { it.length() }
        archives.drop(KEEP_EXPORTS).forEach { file ->
            if (runCatching { file.delete() }.getOrDefault(false)) total -= file.length()
        }
        archives.take(KEEP_EXPORTS).sortedBy { it.lastModified() }.forEach { file ->
            if (total <= MAX_EXPORT_BYTES) return@forEach
            if (runCatching { file.delete() }.getOrDefault(false)) total -= file.length()
        }
    }

    private const val KEEP_EXPORTS = 5
    private const val MAX_EXPORT_BYTES = 50L * 1024 * 1024
}
