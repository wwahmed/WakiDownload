package dev.wakilabs.wakidownload

import dev.wakilabs.wakidownload.core.Kind
import dev.wakilabs.wakidownload.resolve.TwitterResolver
import org.junit.Assert.assertEquals
import org.junit.Test

class TwitterResolverTest {
    /** Expected values come from `node -e` running the exact react-tweet expression. */
    @Test fun syndicationTokenMatchesBrowser() {
        assertEquals("3uchycv2wqc", TwitterResolver.syndicationToken("1585341984679469056"))
        assertEquals("268idzvehmw", TwitterResolver.syndicationToken("896523232098078720"))
    }

    @Test fun radix36MatchesJavaScriptToString() {
        assertEquals("3uc.hycv2wqc", TwitterResolver.doubleToRadix36((1585341984679469056.0 / 1e15) * Math.PI))
        assertEquals("0", TwitterResolver.doubleToRadix36(0.0))
        assertEquals("z", TwitterResolver.doubleToRadix36(35.0))
        assertEquals("10", TwitterResolver.doubleToRadix36(36.0))
        assertEquals("0.i", TwitterResolver.doubleToRadix36(0.5))
    }

    @Test fun parsesSyndicationVideoPickingHighestBitrate() {
        val json = """{"__typename":"Tweet","id_str":"1","mediaDetails":[{"type":"video","media_url_https":"https://pbs.twimg.com/ext_tw_video_thumb/1/pu/img/x.jpg","video_info":{"variants":[
          {"content_type":"application/x-mpegURL","url":"https://video.twimg.com/a.m3u8"},
          {"bitrate":832000,"content_type":"video/mp4","url":"https://video.twimg.com/640x360/a.mp4"},
          {"bitrate":2176000,"content_type":"video/mp4","url":"https://video.twimg.com/1280x720/b.mp4"},
          {"bitrate":256000,"content_type":"video/mp4","url":"https://video.twimg.com/480x270/c.mp4"}]}}]}"""
        val items = TwitterResolver.parseSyndication(json)
        assertEquals(1, items.size)
        assertEquals("https://video.twimg.com/1280x720/b.mp4", items[0].url)
        assertEquals(Kind.VIDEO, items[0].kind)
        assertEquals("mp4", items[0].ext)
    }

    @Test fun parsesSyndicationPhotosAndGif() {
        val json = """{"__typename":"Tweet","mediaDetails":[
          {"type":"photo","media_url_https":"https://pbs.twimg.com/media/DHEXH7RV0AAUwKj.jpg"},
          {"type":"photo","media_url_https":"https://pbs.twimg.com/media/abc.png"},
          {"type":"animated_gif","video_info":{"variants":[{"bitrate":0,"content_type":"video/mp4","url":"https://video.twimg.com/tweet_video/g.mp4"}]}}]}"""
        val items = TwitterResolver.parseSyndication(json)
        assertEquals(3, items.size)
        assertEquals("https://pbs.twimg.com/media/DHEXH7RV0AAUwKj.jpg?name=orig", items[0].url)
        assertEquals("image/jpeg", items[0].mime)
        assertEquals("png", items[1].ext)
        assertEquals(Kind.GIF, items[2].kind)
        assertEquals("mp4", items[2].ext)
    }

    @Test fun parsesFxTwitter() {
        val json = """{"code":200,"tweet":{"id":"1","media":{"all":[{"type":"video","url":"https://video.twimg.com/v.mp4"},{"type":"photo","url":"https://pbs.twimg.com/media/p.jpg"},{"type":"gif","url":"https://video.twimg.com/tweet_video/g.mp4"}]}}}"""
        val items = TwitterResolver.parseFxTwitter(json)
        assertEquals(listOf(Kind.VIDEO, Kind.IMAGE, Kind.GIF), items.map { it.kind })
    }
}
