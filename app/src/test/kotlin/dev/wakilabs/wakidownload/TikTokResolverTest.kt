package dev.wakilabs.wakidownload

import dev.wakilabs.wakidownload.core.Kind
import dev.wakilabs.wakidownload.resolve.TikTokResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TikTokResolverTest {
    private fun page(scopeJson: String, id: String = "__UNIVERSAL_DATA_FOR_REHYDRATION__") =
        "<html><head></head><body><script id=\"$id\" type=\"application/json\">$scopeJson</script><script>x()</script></body></html>"

    @Test fun desktopRehydrationPlayAddr() {
        val html = page("""{"__DEFAULT_SCOPE__":{"webapp.video-detail":{"statusCode":0,"itemInfo":{"itemStruct":{"id":"7626254334065511711","video":{"playAddr":"https://v16-webapp-prime.us.tiktok.com/video/tos/x/?a=1988","downloadAddr":"https://wm.example/watermarked.mp4"}}}}}}""")
        val items = TikTokResolver.parseWebPage(html, mapOf("Cookie" to "ttwid=1"))
        assertEquals(1, items.size)
        assertEquals("https://v16-webapp-prime.us.tiktok.com/video/tos/x/?a=1988", items[0].url)
        assertEquals("ttwid=1", items[0].headers["Cookie"])
        assertEquals(Kind.VIDEO, items[0].kind)
    }

    @Test fun mobileReflowShape() {
        val html = page("""{"__DEFAULT_SCOPE__":{"webapp.reflow.video.detail":{"statusCode":0,"itemInfo":{"itemStruct":{"id":"1","video":{"playAddr":"https://v16.tiktok.com/reflow.mp4"}}}}}}""")
        assertEquals("https://v16.tiktok.com/reflow.mp4", TikTokResolver.parseWebPage(html).single().url)
    }

    @Test fun photoPostImages() {
        val html = page("""{"__DEFAULT_SCOPE__":{"webapp.video-detail":{"itemInfo":{"itemStruct":{"id":"1","imagePost":{"images":[{"imageURL":{"urlList":["https://p16.tiktokcdn.com/a.jpeg?x=1"]}},{"imageURL":{"urlList":["https://p16.tiktokcdn.com/b.webp"]}}]},"video":{"playAddr":"https://ignored.example/slideshow.mp4"}}}}}}""")
        val items = TikTokResolver.parseWebPage(html)
        assertEquals(2, items.size)
        assertEquals(Kind.IMAGE, items[0].kind)
        assertEquals("jpeg", items[0].ext)
        assertEquals("webp", items[1].ext)
    }

    @Test fun sigiStateFallback() {
        val html = page("""{"ItemModule":{"123":{"id":"123","video":{"playAddr":"https://v16.tiktok.com/sigi.mp4"}}}}""", id = "SIGI_STATE")
        assertEquals("https://v16.tiktok.com/sigi.mp4", TikTokResolver.parseWebPage(html).single().url)
    }

    @Test fun bitrateInfoWhenPlayAddrMissingSkipsHevc() {
        val html = page("""{"__DEFAULT_SCOPE__":{"webapp.video-detail":{"itemInfo":{"itemStruct":{"id":"1","video":{"playAddr":"","bitrateInfo":[
          {"Bitrate":410838,"GearName":"normal_720_0","PlayAddr":{"UrlList":["https://v16.tiktok.com/h264_720.mp4"]}},
          {"Bitrate":900000,"GearName":"adapt_1080","CodecType":"h265","PlayAddr":{"UrlList":["https://v16.tiktok.com/media-video-hvc1/x.mp4"]}},
          {"Bitrate":164118,"GearName":"lowest_540_0","PlayAddr":{"UrlList":["https://v16.tiktok.com/h264_540.mp4"]}}]}}}}}}""")
        assertEquals("https://v16.tiktok.com/h264_720.mp4", TikTokResolver.parseWebPage(html).single().url)
    }

    @Test fun unknownShapeYieldsNothing() {
        assertTrue(TikTokResolver.parseWebPage(page("""{"__DEFAULT_SCOPE__":{"webapp.user-detail":{}}}""")).isEmpty())
        assertTrue(TikTokResolver.parseWebPage("<html>login wall</html>").isEmpty())
    }

    @Test fun awemeDetailPrefersPlayAddr() {
        val json = """{"status_code":0,"aweme_detail":{"aweme_id":"1","video":{"play_addr":{"url_list":["https://api.tiktokv.com/play.mp4"]},"download_addr":{"url_list":["https://api.tiktokv.com/watermarked.mp4"]}}}}"""
        val items = TikTokResolver.parseAweme(json)
        assertEquals("https://api.tiktokv.com/play.mp4", items.single().url)
    }

    @Test fun tikwmPrefersHd() {
        val json = """{"code":0,"msg":"success","data":{"play":"/video/media/play/1.mp4","wmplay":"https://x/wm.mp4","hdplay":"https://v16m.tiktokcdn-us.com/hd.mp4"}}"""
        assertEquals("https://v16m.tiktokcdn-us.com/hd.mp4", TikTokResolver.parseTikwm(json).single().url)
        assertEquals("https://www.tikwm.com/video/media/play/1.mp4", TikTokResolver.parseTikwm("""{"code":0,"data":{"play":"/video/media/play/1.mp4"}}""").single().url)
        assertTrue(TikTokResolver.parseTikwm("""{"code":-1,"msg":"nope"}""").isEmpty())
    }
}
