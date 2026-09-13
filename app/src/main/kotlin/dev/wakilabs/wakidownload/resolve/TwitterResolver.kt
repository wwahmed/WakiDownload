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
 * Twitter / X without an API key.
 *
 * Tier 1: the embed syndication endpoint (`cdn.syndication.twimg.com/tweet-result`), the same
 * one twitter.com's own embeds use. It needs a `token` derived from the tweet id (the formula
 * react-tweet ships) and returns full media details, including every mp4 bitrate.
 * Tier 2: fxtwitter's public JSON, a free community mirror, if tier 1 is down or the shape moves.
 * Twitter "GIFs" are mp4 files on the wire; they are saved as mp4 and tagged as GIF in history.
 */
object TwitterResolver {
    private const val TAG = Resolver.TAG

    fun resolve(url: String): Resolved {
        val id = LinkClassifier.tweetId(url) ?: throw ResolveException("No tweet id in $url")
        val syndication = runCatching { fetchSyndication(id) }
            .onFailure { Log.w(TAG, "twitter tier1 (syndication) failed for $id: ${it.message}") }
            .getOrNull()
        if (!syndication.isNullOrEmpty()) return Resolved(Source.TWITTER, id, syndication)

        val fx = runCatching { fetchFxTwitter(id) }
            .onFailure { Log.w(TAG, "twitter tier2 (fxtwitter) failed for $id: ${it.message}") }
            .getOrNull()
        if (!fx.isNullOrEmpty()) return Resolved(Source.TWITTER, id, fx)
        throw ResolveException("No downloadable media found in that tweet")
    }

    private fun fetchSyndication(id: String): List<MediaItem> {
        val url = "https://cdn.syndication.twimg.com/tweet-result?id=$id&token=${syndicationToken(id)}"
        val body = Http.getString(url, mapOf("User-Agent" to Http.UA_DESKTOP, "Accept" to "application/json"))
        val items = parseSyndication(body)
        if (items.isEmpty()) Log.w(TAG, "twitter tier1 unknown shape for $id: ${body.take(300)}")
        return items
    }

    private fun fetchFxTwitter(id: String): List<MediaItem> {
        val body = Http.getString("https://api.fxtwitter.com/status/$id", mapOf("User-Agent" to Http.UA_DESKTOP))
        val items = parseFxTwitter(body)
        if (items.isEmpty()) Log.w(TAG, "twitter tier2 unknown shape for $id: ${body.take(300)}")
        return items
    }

    internal fun parseSyndication(json: String): List<MediaItem> {
        val root = JSONObject(json)
        if (root.optString("__typename") == "TweetTombstone") throw ResolveException("That tweet is unavailable")
        val media = root.optJSONArray("mediaDetails") ?: JSONArray()
        val out = mutableListOf<MediaItem>()
        for (i in 0 until media.length()) {
            val m = media.getJSONObject(i)
            when (m.optString("type")) {
                "photo" -> {
                    val u = m.optString("media_url_https").ifBlank { m.optString("media_url") }
                    if (u.isNotBlank()) {
                        val ext = FileNames.extFromUrl(u, "jpg")
                        out += MediaItem(u.substringBefore('?') + "?name=orig", ext, FileNames.mimeFor(ext), Kind.IMAGE)
                    }
                }
                "video", "animated_gif" -> {
                    val best = bestMp4(m.optJSONObject("video_info")?.optJSONArray("variants"))
                    if (best != null) out += MediaItem(best, "mp4", "video/mp4", if (m.optString("type") == "animated_gif") Kind.GIF else Kind.VIDEO)
                }
            }
        }
        return out
    }

    private fun bestMp4(variants: JSONArray?): String? {
        if (variants == null) return null
        var bestUrl: String? = null
        var bestRate = -1L
        for (i in 0 until variants.length()) {
            val v = variants.getJSONObject(i)
            if (v.optString("content_type") != "video/mp4") continue
            val rate = v.optLong("bitrate", 0)
            if (rate > bestRate) { bestRate = rate; bestUrl = v.optString("url") }
        }
        return bestUrl
    }

    internal fun parseFxTwitter(json: String): List<MediaItem> {
        val tweet = JSONObject(json).optJSONObject("tweet") ?: return emptyList()
        val all = tweet.optJSONObject("media")?.optJSONArray("all") ?: return emptyList()
        val out = mutableListOf<MediaItem>()
        for (i in 0 until all.length()) {
            val m = all.getJSONObject(i)
            val u = m.optString("url")
            if (u.isBlank()) continue
            when (m.optString("type")) {
                "photo" -> { val ext = FileNames.extFromUrl(u, "jpg"); out += MediaItem(u, ext, FileNames.mimeFor(ext), Kind.IMAGE) }
                "video" -> out += MediaItem(u, "mp4", "video/mp4", Kind.VIDEO)
                "gif" -> out += MediaItem(u, "mp4", "video/mp4", Kind.GIF)
            }
        }
        return out
    }

    /**
     * `((Number(id) / 1e15) * Math.PI).toString(36).replace(/(0+|\.)/g, '')`, with the base-36
     * conversion ported from V8's DoubleToRadixCString so the digits match a browser exactly.
     */
    internal fun syndicationToken(id: String): String {
        val value = (id.toDouble() / 1e15) * Math.PI
        return doubleToRadix36(value).replace(Regex("(0+|\\.)"), "")
    }

    private const val DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz"

    internal fun doubleToRadix36(input: Double): String {
        val radix = 36
        var value = input
        val negative = value < 0
        if (negative) value = -value
        var integer = Math.floor(value)
        var fraction = value - integer
        var delta = 0.5 * (Math.nextUp(value) - value)
        delta = maxOf(Math.nextUp(0.0), delta)
        val frac = StringBuilder()
        if (fraction >= delta) {
            do {
                fraction *= radix
                delta *= radix
                val digit = fraction.toInt()
                frac.append(DIGITS[digit])
                fraction -= digit
                if (fraction > 0.5 || (fraction == 0.5 && (digit and 1) == 1)) {
                    if (fraction + delta > 1) {
                        // carry into already-written digits
                        while (true) {
                            if (frac.isEmpty()) { integer += 1; break }
                            val last = frac.last()
                            val d = DIGITS.indexOf(last)
                            frac.setLength(frac.length - 1)
                            if (d + 1 < radix) { frac.append(DIGITS[d + 1]); break }
                        }
                        break
                    }
                }
            } while (fraction >= delta)
        }
        val intPart = StringBuilder()
        do {
            val remainder = integer % radix
            intPart.insert(0, DIGITS[remainder.toInt()])
            integer = (integer - remainder) / radix
        } while (integer > 0)
        val sb = StringBuilder()
        if (negative) sb.append('-')
        sb.append(intPart)
        if (frac.isNotEmpty()) sb.append('.').append(frac)
        return sb.toString()
    }
}
