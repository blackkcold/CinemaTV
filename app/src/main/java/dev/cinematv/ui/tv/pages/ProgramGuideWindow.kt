package dev.cinematv.ui.tv.pages

import dev.cinematv.data.TvProgram

/** One bounded, clock-aware EPG window shared by the native TV guide. */
object ProgramGuideWindow {
    /**
     * Cached XMLTV arrays can contain hours of ended shows. Filter before applying the
     * display bound; keep the cached source untouched so a later clock tick can advance.
     */
    fun window(programs: List<TvProgram>, nowMs: Long, limit: Int = 48): List<TvProgram> = programs
        .asSequence().filter { it.start < it.stop && it.stop > nowMs }
        .distinctBy { Triple(it.start, it.stop, it.title) }
        .sortedWith(compareBy<TvProgram> { it.start }.thenBy { it.stop }.thenBy { it.title })
        .take(limit.coerceIn(0, 48)).toList()
}
