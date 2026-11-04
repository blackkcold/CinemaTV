package dev.cinematv.data.library

import android.content.SharedPreferences
import dev.cinematv.data.LibraryStatus
import dev.cinematv.data.MediaItem
import org.json.JSONArray

/** Raw strings are retained in both the original preferences and the database backup. */
data class LegacyLibraryImport(
    val favorites: List<MediaItem>,
    val history: List<MediaItem>,
    val statuses: List<Pair<LibraryStatus, MediaItem>>,
    val rawValues: Map<String, String> = emptyMap(),
    val skippedRecords: Int = 0
) {
    companion object {
        fun read(prefs: SharedPreferences): LegacyLibraryImport {
            val raw = listOf("favorites", "history", "library_entries").associateWith {
                prefs.getString(it, "[]") ?: "[]"
            }
            var skipped = 0
            fun parse(key: String): List<Pair<LibraryStatus?, MediaItem>> {
                // Malformed arrays abort import before any DB change. Never replace them with an empty list.
                val array = JSONArray(raw.getValue(key))
                return buildList {
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i)
                        if (obj == null) { skipped++; continue }
                        val item = MediaItemCodec.decode(obj)
                        if (item.id.isBlank() || item.title.isBlank()) { skipped++; continue }
                        val status = if (key == "library_entries")
                            runCatching { LibraryStatus.valueOf(obj.optString("state")) }.getOrNull() else null
                        if (key == "library_entries" && status == null) { skipped++; continue }
                        add(status to item)
                    }
                }
            }
            val favorites = parse("favorites").map { it.second }
            val history = parse("history").map { it.second }
            val statuses = parse("library_entries").map { requireNotNull(it.first) to it.second }
            return LegacyLibraryImport(favorites, history, statuses, raw, skipped)
        }
    }
}

data class LibraryImportReport(val imported: Boolean, val importedEntries: Int = 0, val skippedRecords: Int = 0)
