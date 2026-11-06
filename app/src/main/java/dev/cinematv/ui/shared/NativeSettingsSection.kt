package dev.cinematv.ui.shared

/** Stable destinations shared by native settings and the bounded web bridge. */
enum class NativeSettingsSection(val index: Int, val wireName: String, val title: String) {
    CONNECTION(0, "connection", "连接与账户"),
    SOURCES(1, "sources", "片源与订阅"),
    DISPLAY(2, "display", "画面与性能"),
    UPDATES(3, "updates", "关于 CinemaTV"),
    DEVELOPER(4, "developer", "开发者选项"),
    RECOMMENDATIONS(5, "recommendations", "本地推荐"),
    LIBRARY(6, "library", "资料管理");

    companion object {
        fun fromWireName(name: String): NativeSettingsSection? =
            if (name == "advanced") DEVELOPER else entries.firstOrNull { it.wireName == name }
        fun fromIndex(index: Int): NativeSettingsSection = entries.firstOrNull { it.index == index } ?: CONNECTION
    }
}
