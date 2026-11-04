package dev.cinematv.data.library

import org.json.JSONArray
import org.json.JSONObject

/** Unknown versions/corrupt records fail closed and remain untouched in Room. No schema migration. */
object LocalWorkAssociationCodec {
    const val KEY = "local_work_associations_v1"
    fun encode(value: LocalWorkAssociations): String {
        require(value.writable)
        return JSONObject().put("version", 1).put("revision", value.revision)
            .put("entries", array(value.entries)).put("undo", value.undoEntries?.let(::array)).toString()
    }
    fun decode(raw: String?): LocalWorkAssociations {
        if (raw == null) return LocalWorkAssociations()
        return runCatching {
            val json = JSONObject(raw)
            require(integer(json, "version") == 1L)
            LocalWorkAssociations(string(json, "revision").also { require(it.isNotBlank()) },
                entries(json.getJSONArray("entries")),
                if (json.isNull("undo")) null else entries(json.getJSONArray("undo")))
        }.getOrElse { LocalWorkAssociations(writable = false) }
    }
    // JSONObject coercion APIs silently truncate decimals and accept numeric strings. Do not use them.
    private fun integer(json: JSONObject, key: String): Long = when (val value = json.get(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> error("$key must be an integer JSON number")
    }
    private fun string(json: JSONObject, key: String): String =
        (json.get(key) as? String) ?: error("$key must be a JSON string")

    private fun array(entries: Map<String, LocalWorkAssociation>) = JSONArray().also { array ->
        entries.values.sortedBy { it.sourceEntryId }.forEach { entry -> array.put(JSONObject()
            .put("source", entry.sourceEntryId).put("group", entry.groupId)
            .put("season", entry.season ?: JSONObject.NULL).put("createdAt", entry.createdAt)
            .put("updatedAt", entry.updatedAt).put("origin", entry.origin)) }
    }
    private fun entries(array: JSONArray): Map<String, LocalWorkAssociation> {
        val result = linkedMapOf<String, LocalWorkAssociation>()
        repeat(array.length()) { index ->
            val json = array.getJSONObject(index)
            val value = LocalWorkAssociation(string(json, "source"), string(json, "group"),
                if (json.isNull("season")) null else integer(json, "season").also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt(),
                integer(json, "createdAt"), integer(json, "updatedAt"), string(json, "origin"))
            require(value.sourceEntryId.startsWith("entry:") && value.groupId.startsWith("manual-work:"))
            require(value.origin == "user-confirmed" && (value.season == null || value.season >= 0))
            require(value.createdAt >= 0 && value.updatedAt >= value.createdAt)
            require(result.put(value.sourceEntryId, value) == null)
        }
        return result
    }
}
