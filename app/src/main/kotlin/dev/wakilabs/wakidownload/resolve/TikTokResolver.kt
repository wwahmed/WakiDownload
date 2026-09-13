package dev.wakilabs.wakidownload.resolve

import android.util.Log
import dev.wakilabs.wakidownload.core.FileNames
import dev.wakilabs.wakidownload.core.Http
import dev.wakilabs.wakidownload.core.Kind
import dev.wakilabs.wakidownload.core.LinkClassifier
import dev.wakilabs.wakidownload.core.MediaItem
import dev.wakilabs.wakidownload.core.ResolveException
import dev.wakilabs.wakidownload.core.Resolved
import dev.wakilabs.wakidownload.core.Source
import org.json.JSONArray
import org.json.JSONObject

/**
 * TikTok without a watermark and without an API key.
 *
 * Tier 1: the aweme detail API, `play_addr` (the stream the app plays; `download_addr` is the
 *         one with the burned-in watermark, never used). Short timeout: the endpoint has been
 *         observed answering 302 → 504, so a dead tier must not stall the share sheet.
 * Tier 2: the public web page. `__UNIVERSAL_DATA_FOR_REHYDRATION__` (desktop shape
 *         `webapp.video-detail`, mobile shape `webapp.reflow.video.detail`) or the older
 *         `SIGI_STATE` blob, both carrying `video.playAddr` (watermark-free, verified by frame
 *         inspection) plus `imagePost.images[]` for photo posts. The CDN requires the page's
 *         cookies and a tiktok.com referer, so those ride along in the item headers.
 * Tier 3: tikwm.com's free JSON (`play` = no watermark), the community fallback of last resort.
 * Anything that answers with a shape we do not recognise is logged with a snippet under
 * the WakiDownload tag so the next fix starts from evidence.
 */
object TikTokResolver {
    private const val TAG = Resolver.TAG

    fun resolve(url: String): Resolved {
        val (client, jar) = Http.cookieClient()
        var pageUrl = url
        if (LinkClassifier.tiktokId(pageUrl) == null) {
            pageUrl = runCatching { Http.resolveFinalUrl(pageUrl, Http.UA_MOBILE, client) }
                .onFailure { Log.w(TAG, "tiktok short link resolve failed for $url: ${it.message}") }
                .getOrDefault(pageUrl)
        }
        val id = LinkClassifier.tiktokId(pageUrl) ?: throw ResolveException("No TikTok video id in $pageUrl")

        runCatching { fetchAwemeDetail(id) }
            .onFailure { Log.w(TAG, "tiktok tier1 (aweme detail) failed for $id: ${it.message}") }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return Resolved(Source.TIKTOK, id, it) }

        runCatching { fetchWebPage(pageUrl, client, jar) }
            .onFailure { Log.w(TAG, "tiktok tier2 (web page) failed for $id: ${it.message}") }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return Resolved(Source.TIKTOK, id, it) }

        runCatching { fetchTikwm(pageUrl) }
            .onFailure { Log.w(TAG, "tiktok tier3 (tikwm) failed for $id: ${it.message}") }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return Resolved(Source.TIKTOK, id, it) }

        throw ResolveException("Could not find the TikTok media (all three sources failed)")
    }

    // Tier 1

    private fun fetchAwemeDetail(id: String): List<MediaItem> {
        val url = "https://www.tiktok.com/aweme/v1/aweme/detail/?aweme_id=$id&aid=1180&device_platform=android&version_code=300904"
        val body = Http.getString(url, mapOf("User-Agent" to Http.UA_TIKTOK_APP, "Accept" to "application/json"), timeoutSeconds = 8)
        val items = parseAweme(body)
        if (items.isEmpty()) Log.w(TAG, "tiktok tier1 unknown shape for $id: ${body.take(300)}")
        return items
    }

    internal fun parseAweme(json: String): List<MediaItem> {
        val root = JSONObject(json)
        val detail = root.optJSONObject("aweme_detail")
            ?: root.optJSONArray("aweme_list")?.optJSONObject(0)
            ?: return emptyList()
        val headers = mapOf("User-Agent" to Http.UA_TIKTOK_APP)
        detail.optJSONObject("image_post_info")?.optJSONArray("images")?.let { images ->
            val out = mutableListOf<MediaItem>()
            for (i in 0 until images.length()) {
                val u = images.getJSONObject(i).optJSONObject("display_image")?.optJSONArray("url_list")?.optString(0)
                if (!u.isNullOrBlank()) { val ext = FileNames.extFromUrl(u, "jpeg"); out += MediaItem(u, ext, FileNames.mimeFor(ext), Kind.IMAGE, headers) }
            }
            if (out.isNotEmpty()) return out
        }
        val play = detail.optJSONObject("video")?.optJSONObject("play_addr")?.optJSONArray("url_list")?.optString(0)
        return if (play.isNullOrBlank()) emptyList() else listOf(MediaItem(play, "mp4", "video/mp4", Kind.VIDEO, headers))
    }

    // Tier 2

    private fun fetchWebPage(pageUrl: String, client: okhttp3.OkHttpClient, jar: dev.wakilabs.wakidownload.core.MemoryCookieJar): List<MediaItem> {
        val html = Http.getString(
            pageUrl,
            mapOf("User-Agent" to Http.UA_DESKTOP, "Accept" to "text/html,application/xhtml+xml", "Accept-Language" to "en-US,en;q=0.9"),
            client,
        )
        val cookie = jar.headerFor("tiktok.com")
        val headers = mapOf("User-Agent" to Http.UA_DESKTOP, "Referer" to "https://www.tiktok.com/", "Cookie" to cookie)
        val items = parseWebPage(html, headers)
        if (items.isEmpty()) {
            val hint = when {
                "__UNIVERSAL_DATA_FOR_REHYDRATION__" in html -> "rehydration present but no media: " + (extractScript(html, "__UNIVERSAL_DATA_FOR_REHYDRATION__")?.take(400) ?: "")
                "SIGI_STATE" in html -> "SIGI_STATE present but no media"
                else -> "no known state blob; html starts: " + html.take(200)
            }
            Log.w(TAG, "tiktok tier2 unknown shape for $pageUrl: $hint")
        }
        return items
    }

    internal fun parseWebPage(html: String, headers: Map<String, String> = emptyMap()): List<MediaItem> {
        extractScript(html, "__UNIVERSAL_DATA_FOR_REHYDRATION__")?.let { json ->
            val scope = JSONObject(json).optJSONObject("__DEFAULT_SCOPE__") ?: JSONObject()
            val item = scope.optJSONObject("webapp.video-detail")?.optJSONObject("itemInfo")?.optJSONObject("itemStruct")
                ?: scope.optJSONObject("webapp.reflow.video.detail")?.optJSONObject("itemInfo")?.optJSONObject("itemStruct")
            if (item != null) return itemsFromItemStruct(item, headers)
        }
        extractScript(html, "SIGI_STATE")?.let { json ->
            val module = JSONObject(json).optJSONObject("ItemModule") ?: return@let
            val key = module.keys().asSequence().firstOrNull() ?: return@let
            return itemsFromItemStruct(module.getJSONObject(key), headers)
        }
        return emptyList()
    }

    private fun itemsFromItemStruct(item: JSONObject, headers: Map<String, String>): List<MediaItem> {
        item.optJSONObject("imagePost")?.optJSONArray("images")?.let { images ->
            val out = mutableListOf<MediaItem>()
            for (i in 0 until images.length()) {
                val u = images.getJSONObject(i).optJSONObject("imageURL")?.optJSONArray("urlList")?.optString(0)
                if (!u.isNullOrBlank()) { val ext = FileNames.extFromUrl(u, "jpeg"); out += MediaItem(u, ext, FileNames.mimeFor(ext), Kind.IMAGE, headers) }
            }
            if (out.isNotEmpty()) return out
        }
        val video = item.optJSONObject("video") ?: return emptyList()
        val play = video.optString("playAddr").ifBlank {
            // some shapes only fill bitrateInfo; take the highest-bitrate H.264 rendition
            bestBitrate(video.optJSONArray("bitrateInfo"))
        }
        return if (play.isBlank()) emptyList() else listOf(MediaItem(play, "mp4", "video/mp4", Kind.VIDEO, headers))
    }

    private fun bestBitrate(arr: JSONArray?): String {
        if (arr == null) return ""
        var best = ""
        var rate = -1L
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            val u = b.optJSONObject("PlayAddr")?.optJSONArray("UrlList")?.optString(0) ?: continue
            if (b.optString("CodecType", "h264").contains("265") || u.contains("hvc1")) continue
            val r = b.optLong("Bitrate", 0)
            if (r > rate) { rate = r; best = u }
        }
        return best
    }

    internal fun extractScript(html: String, id: String): String? {
        val start = Regex("""<script[^>]*\bid="$id"[^>]*>""").find(html) ?: return null
        val end = html.indexOf("</script>", start.range.last)
        if (end < 0) return null
        return html.substring(start.range.last + 1, end).trim()
    }

    // Tier 3

    private fun fetchTikwm(pageUrl: String): List<MediaItem> {
        val body = Http.getString(
            "https://www.tikwm.com/api/?hd=1&url=" + java.net.URLEncoder.encode(pageUrl, "UTF-8"),
            mapOf("User-Agent" to Http.UA_DESKTOP, "Accept" to "application/json"),
        )
        val items = parseTikwm(body)
        if (items.isEmpty()) Log.w(TAG, "tiktok tier3 unknown shape: ${body.take(300)}")
        return items
    }

    internal fun parseTikwm(json: String): List<MediaItem> {
        val root = JSONObject(json)
        if (root.optInt("code", -1) != 0) return emptyList()
        val data = root.optJSONObject("data") ?: return emptyList()
        val headers = mapOf("User-Agent" to Http.UA_DESKTOP)
        data.optJSONArray("images")?.let { images ->
            val out = mutableListOf<MediaItem>()
            for (i in 0 until images.length()) {
                val u = images.optString(i)
                if (u.isNotBlank()) { val ext = FileNames.extFromUrl(u, "jpeg"); out += MediaItem(u, ext, FileNames.mimeFor(ext), Kind.IMAGE, headers) }
            }
            if (out.isNotEmpty()) return out
        }
        val play = data.optString("hdplay").ifBlank { data.optString("play") }
        if (play.isBlank()) return emptyList()
        val abs = if (play.startsWith("http")) play else "https://www.tikwm.com$play"
        return listOf(MediaItem(abs, "mp4", "video/mp4", Kind.VIDEO, headers))
    }
}
