package dev.wakilabs.wakidownload.resolve

import android.util.Log
import dev.wakilabs.wakidownload.core.FileNames
import dev.wakilabs.wakidownload.core.Http
import dev.wakilabs.wakidownload.core.Kind
import dev.wakilabs.wakidownload.core.MediaItem
import dev.wakilabs.wakidownload.core.ResolveException
import dev.wakilabs.wakidownload.core.Resolved
import dev.wakilabs.wakidownload.core.Source
import okhttp3.Request
import java.security.MessageDigest

/**
 * Any other link shared from any app (WhatsApp, a browser, Telegram...). A direct image or video
 * URL is saved as is; an HTML page is scanned for `og:video` / `twitter:player:stream` / `og:image`.
 */
object GenericResolver {
    private const val TAG = Resolver.TAG
    private val META = Regex("""<meta\s+[^>]*?(?:property|name)\s*=\s*["']([^"']+)["'][^>]*?content\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val META_REV = Regex("""<meta\s+[^>]*?content\s*=\s*["']([^"']+)["'][^>]*?(?:property|name)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    fun resolve(url: String): Resolved {
        val id = shortHash(url)
        val req = Request.Builder().url(url).header("User-Agent", Http.UA_MOBILE).header("Accept", "text/html,video/*,image/*,*/*;q=0.8").build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ResolveException("That link answered HTTP ${resp.code}")
            val type = resp.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase() ?: ""
            val finalUrl = resp.request.url.toString()
            if (type.startsWith("image/") || type.startsWith("video/")) {
                resp.close()
                return Resolved(Source.WEB, id, listOf(direct(finalUrl, type)))
            }
            if (!type.contains("html")) throw ResolveException("That link is not a video, picture or web page ($type)")
            val html = resp.body?.let { b -> b.source().use { it.readUtf8(minOf(b.contentLength().takeIf { l -> l > 0 } ?: (512L * 1024), 512L * 1024)) } } ?: ""
            val items = fromHtml(html, finalUrl)
            if (items.isEmpty()) {
                Log.w(TAG, "generic unknown shape for $finalUrl: no og:video/og:image; html starts: ${html.take(200)}")
                throw ResolveException("No video or picture found on that page")
            }
            return Resolved(Source.WEB, id, items)
        }
    }

    internal fun fromHtml(html: String, pageUrl: String): List<MediaItem> {
        val meta = LinkedHashMap<String, String>()
        META.findAll(html).forEach { m -> meta.putIfAbsent(m.groupValues[1].lowercase(), m.groupValues[2]) }
        META_REV.findAll(html).forEach { m -> meta.putIfAbsent(m.groupValues[2].lowercase(), m.groupValues[1]) }
        val video = listOf("og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream").firstNotNullOfOrNull { meta[it] }
            ?.takeIf { it.startsWith("http") && !it.contains("youtube.com/embed") && !it.endsWith(".swf") }
        if (video != null) {
            val ext = FileNames.extFromUrl(video, "mp4")
            return listOf(MediaItem(absolute(video, pageUrl), ext, FileNames.mimeFor(ext), Kind.VIDEO, mapOf("User-Agent" to Http.UA_MOBILE, "Referer" to pageUrl)))
        }
        val image = listOf("og:image:secure_url", "og:image:url", "og:image", "twitter:image").firstNotNullOfOrNull { meta[it] }?.takeIf { it.isNotBlank() }
        if (image != null) {
            val ext = FileNames.extFromUrl(image, "jpg")
            return listOf(MediaItem(absolute(image, pageUrl), ext, FileNames.mimeFor(ext), Kind.IMAGE, mapOf("User-Agent" to Http.UA_MOBILE, "Referer" to pageUrl)))
        }
        return emptyList()
    }

    private fun direct(url: String, type: String): MediaItem {
        val ext = FileNames.extFromUrl(url, when (type) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; "video/webm" -> "webm"; else -> "mp4" })
        val kind = if (type.startsWith("image/")) (if (type == "image/gif") Kind.GIF else Kind.IMAGE) else Kind.VIDEO
        return MediaItem(url, ext, type, kind, mapOf("User-Agent" to Http.UA_MOBILE))
    }

    private fun absolute(u: String, base: String): String =
        if (u.startsWith("http")) u else runCatching { java.net.URL(java.net.URL(base), u).toString() }.getOrDefault(u)

    internal fun shortHash(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).take(3).joinToString("") { "%02x".format(it) }
}
