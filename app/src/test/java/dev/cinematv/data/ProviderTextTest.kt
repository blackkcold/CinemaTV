package dev.cinematv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProviderTextTest {
    @Test fun providerHtmlBecomesReadablePlainTextWithoutChangingTheWords() {
        val raw = "<p><span style=\"color: rgb(17, 17, 17);\">佐藤真冬、上ノ山立夏</span><br/>" +
            "<span style=\"font-family: Helvetica;\">バンド「ギヴン」&amp; 仲間</span></p>"
        val text = plainProviderText(raw)
        assertEquals("佐藤真冬、上ノ山立夏\nバンド「ギヴン」& 仲間", text)
        assertFalse(text.contains("<"))
        assertFalse(text.contains("style="))
    }

    @Test fun blankAndEntityOnlyDescriptionsStayEmptyOrReadable() {
        assertEquals("", plainProviderText(""))
        assertEquals("A & B < C", plainProviderText("A &amp; B &lt; C"))
        assertEquals("x y", plainProviderText("x&nbsp;&nbsp;y"))
    }
}
