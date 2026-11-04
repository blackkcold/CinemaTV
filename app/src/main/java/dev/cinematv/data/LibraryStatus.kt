package dev.cinematv.data

enum class LibraryStatus(val title: String) {
    WANT_TO_WATCH("想看"),
    WATCHING("在看"),
    WATCHED("看过"),
    FAVORITE("收藏"),
    DROPPED("弃剧")
}

/** Do not merge unrelated CMS vod IDs just because their titles happen to match. */
object LibraryIdentity {
    fun key(item: MediaItem): String = item.id + "|" + (item.source?.url ?: "")
}
