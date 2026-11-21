package com.constx.onvyra.ui.pages.novel

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderCoverFallbackTest {
    @Test fun emptyImageAttributesMustNotBecomeHtmlPageCoverUrls() {
        for (html in listOf("<img>", "<img src=''>", "<img src='data:image/gif;base64,R0'>", "<img src='/nocover.jpg'>")) {
            val image = Jsoup.parse(html).selectFirst("img")
            assertEquals("" to "", NovelHtmlParser.image(image, "https://www.piquge.com/123/456/"))
        }
    }
}
