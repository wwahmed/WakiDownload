package dev.wakilabs.wakidownload

import dev.wakilabs.wakidownload.core.FileNames
import dev.wakilabs.wakidownload.core.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {
    @Test fun singleItemName() {
        val n = FileNames.build(Source.TIKTOK, "7626254334065511711", "mp4", 0, 1, at = 0L)
        assertTrue(n, Regex("""tiktok-\d{8}-\d{6}-511711\.mp4""").matches(n))
    }

    @Test fun multiItemNamesAreNumbered() {
        val a = FileNames.build(Source.TWITTER, "896523232098078720", "jpg", 0, 3, at = 0L)
        val b = FileNames.build(Source.TWITTER, "896523232098078720", "jpg", 2, 3, at = 0L)
        assertTrue(a, Regex("""twitter-\d{8}-\d{6}-078720-1\.jpg""").matches(a))
        assertTrue(b, Regex("""twitter-\d{8}-\d{6}-078720-3\.jpg""").matches(b))
    }

    @Test fun extensionFromUrl() {
        assertEquals("jpg", FileNames.extFromUrl("https://pbs.twimg.com/media/DHEXH7RV0AAUwKj.jpg?name=orig", "png"))
        assertEquals("mp4", FileNames.extFromUrl("https://video.twimg.com/x/vid/1280x720/abc.mp4?tag=12", "bin"))
        assertEquals("mp4", FileNames.extFromUrl("https://v16-webapp-prime.us.tiktok.com/video/tos/useast8/x/?a=1988", "mp4"))
        assertEquals("video/mp4", FileNames.mimeFor("mp4"))
        assertEquals("image/jpeg", FileNames.mimeFor("JPG"))
    }
}
