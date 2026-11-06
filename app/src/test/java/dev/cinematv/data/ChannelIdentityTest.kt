package dev.cinematv.data

import org.junit.Assert.*
import org.junit.Test

class ChannelIdentityTest {
    private val source = LiveSource("feed", "Feed", "https://feed.example.test/live.m3u")
    private val channel = TvChannel("same-tvg", "Channel", "https://media.example.test/hd.m3u8", tvgId = "same-tvg")

    @Test fun sameEpgIdDifferentLinesAndSourcesHaveDifferentKeys() {
        assertNotEquals(ChannelIdentity.key(source, channel),
            ChannelIdentity.key(source, channel.copy(url = "https://media.example.test/sd.m3u8")))
        assertNotEquals(ChannelIdentity.key(source, channel),
            ChannelIdentity.key(source.copy(url = "https://feed.example.test/second.m3u"), channel))
        assertEquals(ChannelIdentity.key(source, channel), ChannelIdentity.key(source, channel.copy(name = "New name")))
    }

    @Test fun componentSeparatorsCannotCollide() {
        assertNotEquals(ChannelIdentity.key(source.copy(key = "a|b"), channel.copy(id = "c")),
            ChannelIdentity.key(source.copy(key = "a"), channel.copy(id = "b|c")))
    }
}
