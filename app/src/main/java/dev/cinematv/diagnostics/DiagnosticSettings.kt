package dev.cinematv.diagnostics

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Persisted outside backup; default off. Non-dev channels always force logging off. */
class DiagnosticSettings private constructor(private val file: File) {
    var enabled: Boolean = false
        private set
    var level: DiagnosticLevel = DiagnosticLevel.BASIC
        private set

    fun setEnabled(value: Boolean) { enabled = value; save() }
    fun setLevel(value: DiagnosticLevel) { level = value; save() }

    /**
     * AtomicFile permits one writer at a time; log calls arrive from independent coroutines,
     * so writes are serialized here rather than losing one of two near-simultaneous changes.
     */
    @Synchronized
    private fun save() {
        runCatching {
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                stream.write(JSONObject().put("version", 1).put("enabled", enabled)
                    .put("level", level.name).toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Exception) {
                atomic.failWrite(stream)
                throw error
            }
        }
    }

    companion object {
        fun load(context: Context): DiagnosticSettings {
            val settings = DiagnosticSettings(File(context.noBackupFilesDir, "diagnostic-log.v1"))
            runCatching {
                val raw = AtomicFile(File(context.noBackupFilesDir, "diagnostic-log.v1")).readFully()
                    .toString(Charsets.UTF_8)
                val value = JSONObject(raw)
                settings.enabled = value.optBoolean("enabled", false)
                settings.level = runCatching { DiagnosticLevel.valueOf(value.optString("level")) }
                    .getOrDefault(DiagnosticLevel.BASIC)
            }
            return settings
        }
    }
}
