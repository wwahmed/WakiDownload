package dev.wakilabs.wakidownload.core

import java.net.URI

/** Pure functions over shared text and URLs. No network. */
object LinkClassifier {
    private val URL_REGEX = Regex("""https?://[^\s<>"'\)\]]+""", RegexOption.IGNORE_CASE)
    private val TWEET_ID = Regex("""/status(?:es)?/(\d{5,})""")
    private val TIKTOK_ID = Regex("""/(?:video|photo|v|embed/v2)/(\d{6,})""")

    private val TWITTER_HOSTS = setOf("twitter.com", "x.com", "mobile.twitter.com", "www.twitter.com", "www.x.com", "t.co")
    private val TIKTOK_HOSTS = setOf("tiktok.com", "www.tiktok.com", "vm.tiktok.com", "vt.tiktok.com", "m.tiktok.com")
    private val SHORTENER_HOSTS = setOf("t.co", "vm.tiktok.com", "vt.tiktok.com")

    /** First URL in the shared text; a known Twitter or TikTok URL wins over an earlier unknown one. */
    fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val all = URL_REGEX.findAll(text).map { it.value.trimEnd('.', ',', ';', '!', '?') }.toList()
        return all.firstOrNull { classify(it) != Source.UNKNOWN } ?: all.firstOrNull()
    }

    fun host(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()

    fun classify(url: String): Source {
        val h = host(url) ?: return Source.UNKNOWN
        return when {
            h in TWITTER_HOSTS -> Source.TWITTER
            h in TIKTOK_HOSTS || h.endsWith(".tiktok.com") -> Source.TIKTOK
            else -> Source.UNKNOWN
        }
    }

    /** Short links carry no id; they must be followed before the host can be read. */
    fun isShortener(url: String): Boolean {
        val h = host(url) ?: return false
        if (h in SHORTENER_HOSTS) return true
        // www.tiktok.com/t/XXXX is the desktop share short form
        return h.endsWith("tiktok.com") && Regex("""^/t/[A-Za-z0-9]+/?$""").containsMatchIn(runCatching { URI(url).path }.getOrNull() ?: "")
    }

    fun tweetId(url: String): String? = TWEET_ID.find(url)?.groupValues?.get(1)

    fun tiktokId(url: String): String? = TIKTOK_ID.find(url)?.groupValues?.get(1)

    /** The last six digits of a post id: enough to tell downloads apart, short enough for a filename. */
    fun shortId(id: String): String = id.takeLast(6)
}
