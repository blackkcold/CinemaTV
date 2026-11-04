package dev.cinematv.data

/** Provider state is explicit: absence of licensed data never becomes a fake rating. */
data class MetadataCatalog(
    val items: List<MediaItem> = emptyList(),
    val provider: String = "",
    val attribution: String = "",
    val available: Boolean = false,
    val nextOffset: Int? = null
)
