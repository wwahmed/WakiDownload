package dev.wakilabs.wakidownload

import dev.wakilabs.wakidownload.core.Kind
import dev.wakilabs.wakidownload.resolve.GenericResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericResolverTest {
    @Test fun ogVideoWins() {
        val html = """<html><head><meta property="og:image" content="https://cdn.example/poster.jpg"><meta property="og:video:secure_url" content="https://cdn.example/clip.mp4?x=1"></head></html>"""
        val items = GenericResolver.fromHtml(html, "https://example.com/post/1")
        assertEquals(1, items.size)
        assertEquals("https://cdn.example/clip.mp4?x=1", items[0].url)
        assertEquals(Kind.VIDEO, items[0].kind)
        assertEquals("https://example.com/post/1", items[0].headers["Referer"])
    }

    @Test fun ogImageFallbackAndReversedAttributeOrder() {
        val html = """<meta content="/img/hero.png" property="og:image">"""
        val items = GenericResolver.fromHtml(html, "https://example.com/a/b")
        assertEquals("https://example.com/img/hero.png", items.single().url)
        assertEquals(Kind.IMAGE, items[0].kind)
        assertEquals("png", items[0].ext)
    }

    @Test fun youtubeEmbedIsNotAVideoFile() {
        val html = """<meta property="og:video" content="https://www.youtube.com/embed/abc"><meta property="og:image" content="https://i.ytimg.com/vi/abc/hq.jpg">"""
        assertEquals(Kind.IMAGE, GenericResolver.fromHtml(html, "https://youtube.com/watch?v=abc").single().kind)
    }

    @Test fun nothingWhenNoMeta() {
        assertTrue(GenericResolver.fromHtml("<html><body>hi</body></html>", "https://x.example").isEmpty())
    }

    @Test fun shortHashIsStableAndShort() {
        assertEquals(6, GenericResolver.shortHash("https://example.com/x").length)
        assertEquals(GenericResolver.shortHash("https://example.com/x"), GenericResolver.shortHash("https://example.com/x"))
    }
}
