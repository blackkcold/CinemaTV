package dev.cinematv.ui.tv.focus

internal data class LazyFocusTarget(val index: Int, val itemKey: String)

/**
 * Resolve against today's order. A present key wins; a removed item falls back to its
 * old position in the same region, clamped to the new tail, not blindly to the first item.
 */
internal fun resolveLazyFocusTarget(
    itemKeys: List<String>,
    savedKey: String,
    fallbackIndex: Int = 0,
): LazyFocusTarget? {
    if (itemKeys.isEmpty()) return null
    val exact = itemKeys.indexOf(savedKey)
    val index = if (exact >= 0) exact else fallbackIndex.coerceIn(0, itemKeys.lastIndex)
    return LazyFocusTarget(index, itemKeys[index])
}
