package dev.cinematv.data.douban.public

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in local probe. It reads pages captured by the evidence script and exercises the
 * production parser against real markup. It is skipped unless DOUBAN_LIVE_DIR is set,
 * so CI never needs the network or captured pages.
 */
class LiveDoubanParserProbeTest {
    private val dir = System.getenv("DOUBAN_LIVE_DIR")?.let(::File)

    @Test fun parseLivePages() {
        assumeTrue(dir != null)
        val top = DoubanPublicParser.top250(File(dir, "top250-page1.html").readText(), 0)
        println("LIVE top250-p1 count=${top.size} rank0=${top.firstOrNull()?.rank} rankN=${top.lastOrNull()?.rank} " +
            "id=${top.firstOrNull()?.subjectId} rating=${top.firstOrNull()?.rating} cover=${top.firstOrNull()?.cover?.isNotBlank()}")
        val last = DoubanPublicParser.top250(File(dir, "top250-page10.html").readText(), 225)
        println("LIVE top250-p10 count=${last.size} rank0=${last.firstOrNull()?.rank} rankN=${last.lastOrNull()?.rank} " +
            "rating=${last.firstOrNull()?.rating}")
        val browse = DoubanPublicParser.newSearch(File(dir, "browse-suspense.json").readText(), 0, DoubanPublicKind.MOVIE)
        println("LIVE browse count=${browse.size} id=${browse.firstOrNull()?.subjectId} " +
            "rating=${browse.firstOrNull()?.rating} cover=${browse.firstOrNull()?.cover?.isNotBlank()}")
        val hot = DoubanPublicParser.hot(File(dir, "hot-movie.json").readText(), DoubanPublicKind.MOVIE)
        println("LIVE hot count=${hot.size} id=${hot.firstOrNull()?.subjectId} rating=${hot.firstOrNull()?.rating} " +
            "cover=${hot.firstOrNull()?.cover?.isNotBlank()}")
        val doulist = DoubanPublicParser.doulist(File(dir, "doulist-oscar.html").readText())
        println("LIVE doulist count=${doulist.size} id=${doulist.firstOrNull()?.subjectId} " +
            "rating=${doulist.firstOrNull()?.rating} cover=${doulist.firstOrNull()?.cover?.isNotBlank()}")
        check(top.size == 25) { "top250 page 1 parsed ${top.size}" }
        check(last.size == 25) { "top250 page 10 parsed ${last.size}" }
        check(top.first()?.rank == 1 && last.last()?.rank == 250)
        check(browse.size >= 15) { "browse parsed ${browse.size}" }
        check(hot.size >= 30) { "hot parsed ${hot.size}" }
        check(doulist.size >= 10) { "doulist parsed ${doulist.size}" }
        check(top.first()?.rating?.toDoubleOrNull() != null)
        check(top.first()?.cover?.isNotBlank() == true)
    }
}
