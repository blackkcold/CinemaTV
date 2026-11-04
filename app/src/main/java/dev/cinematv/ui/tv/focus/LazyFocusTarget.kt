package dev.cinematv.ui.tv.focus

internal data class LazyFocusTarget(val index: Int, val itemKey: String)

/** Resolve against today's order; a removed item falls back within its rail. */
internal fun resolveLazyFocusTarget(itemKeys: List<String>, savedKey: String): LazyFocusTarget? {
    if (itemKeys.isEmpty()) return null
    val index = itemKeys.indexOf(savedKey).coerceAtLeast(0)
    return LazyFocusTarget(index, itemKeys[index])
}
