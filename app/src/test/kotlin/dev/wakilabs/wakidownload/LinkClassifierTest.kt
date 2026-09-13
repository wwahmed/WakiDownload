package dev.wakilabs.wakidownload

import dev.wakilabs.wakidownload.core.LinkClassifier
import dev.wakilabs.wakidownload.core.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkClassifierTest {
    @Test fun extractsFirstUrlFromShareText() {
        assertEquals("https://x.com/nasa/status/1585341984679469056?s=20", LinkClassifier.extractUrl("Look at this https://x.com/nasa/status/1585341984679469056?s=20 wow"))
        assertEquals("https://vm.tiktok.com/ZMabc123/", LinkClassifier.extractUrl("Check out this TikTok! https://vm.tiktok.com/ZMabc123/"))
        assertNull(LinkClassifier.extractUrl("no links here"))
        assertNull(LinkClassifier.extractUrl(null))
    }

    @Test fun prefersKnownHostOverEarlierUnknown() {
        assertEquals("https://twitter.com/a/status/12345", LinkClassifier.extractUrl("https://example.com/x and https://twitter.com/a/status/12345"))
    }

    @Test fun stripsTrailingPunctuation() {
        assertEquals("https://x.com/a/status/12345", LinkClassifier.extractUrl("see https://x.com/a/status/12345."))
        assertEquals("https://x.com/a/status/12345", LinkClassifier.extractUrl("(https://x.com/a/status/12345)"))
    }

    @Test fun classifiesHosts() {
        listOf("https://twitter.com/a/status/1", "https://x.com/a/status/1", "https://mobile.twitter.com/a/status/1", "https://t.co/abc").forEach {
            assertEquals(it, Source.TWITTER, LinkClassifier.classify(it))
        }
        listOf("https://www.tiktok.com/@a/video/7626254334065511711", "https://vm.tiktok.com/ZM/", "https://vt.tiktok.com/ZS/", "https://m.tiktok.com/v/1.html", "https://tiktok.com/t/ZT/").forEach {
            assertEquals(it, Source.TIKTOK, LinkClassifier.classify(it))
        }
        assertEquals(Source.UNKNOWN, LinkClassifier.classify("https://instagram.com/p/x"))
        assertEquals(Source.UNKNOWN, LinkClassifier.classify("https://tiktok.com.evil.example/x"))
    }

    @Test fun shorteners() {
        assertTrue(LinkClassifier.isShortener("https://t.co/abc"))
        assertTrue(LinkClassifier.isShortener("https://vm.tiktok.com/ZMabc/"))
        assertTrue(LinkClassifier.isShortener("https://vt.tiktok.com/ZSabc/"))
        assertTrue(LinkClassifier.isShortener("https://www.tiktok.com/t/ZTabc/"))
        assertFalse(LinkClassifier.isShortener("https://www.tiktok.com/@a/video/7626254334065511711"))
        assertFalse(LinkClassifier.isShortener("https://x.com/a/status/1"))
    }

    @Test fun ids() {
        assertEquals("1585341984679469056", LinkClassifier.tweetId("https://x.com/elonmusk/status/1585341984679469056?s=20&t=x"))
        assertEquals("7626254334065511711", LinkClassifier.tiktokId("https://www.tiktok.com/@complex/video/7626254334065511711?_r=1"))
        assertEquals("7626254334065511711", LinkClassifier.tiktokId("https://www.tiktok.com/@complex/photo/7626254334065511711"))
        assertNull(LinkClassifier.tiktokId("https://vm.tiktok.com/ZMabc/"))
        assertEquals("511711", LinkClassifier.shortId("7626254334065511711"))
    }
}
