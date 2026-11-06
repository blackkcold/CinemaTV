package dev.cinematv.hybrid

import dev.cinematv.data.TvProgram

object HybridProgramGuide {
    /** Cached XMLTV arrays can contain hours of ended shows. Filter before applying the DTO bound. */
    fun window(programs: List<TvProgram>, nowMs: Long, limit: Int = 48): List<TvProgram> = programs
        .asSequence().filter { it.start < it.stop && it.stop > nowMs }
        .distinctBy { Triple(it.start, it.stop, it.title) }
        .sortedWith(compareBy<TvProgram> { it.start }.thenBy { it.stop }.thenBy { it.title })
        .take(limit.coerceIn(0, 48)).toList()
}
