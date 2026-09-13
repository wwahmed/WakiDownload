package dev.wakilabs.wakidownload.core

enum class Source(val slug: String, val label: String) {
    TWITTER("twitter", "Twitter / X"),
    TIKTOK("tiktok", "TikTok"),
    WEB("web", "Web link"),
    SHARED("shared", "Shared file"),
    UNKNOWN("unknown", "Unknown"),
}

enum class Kind { VIDEO, IMAGE, GIF }

/** One downloadable file. [headers] carry whatever the CDN needs (cookies, referer, UA). */
data class MediaItem(
    val url: String,
    val ext: String,
    val mime: String,
    val kind: Kind,
    val headers: Map<String, String> = emptyMap(),
)

data class Resolved(val source: Source, val id: String, val items: List<MediaItem>)

class ResolveException(message: String, cause: Throwable? = null) : Exception(message, cause)
