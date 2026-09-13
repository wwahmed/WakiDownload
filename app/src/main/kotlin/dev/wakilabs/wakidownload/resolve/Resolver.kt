package dev.wakilabs.wakidownload.resolve

import android.util.Log
import dev.wakilabs.wakidownload.core.Http
import dev.wakilabs.wakidownload.core.LinkClassifier
import dev.wakilabs.wakidownload.core.ResolveException
import dev.wakilabs.wakidownload.core.Resolved
import dev.wakilabs.wakidownload.core.Source

object Resolver {
    const val TAG = "WakiDownload"

    /** Shared text → final URL → host → media list. Throws [ResolveException] with a user-readable message. */
    fun resolve(sharedText: String): Resolved {
        val raw = LinkClassifier.extractUrl(sharedText) ?: throw ResolveException("No link found in what was shared")
        var url = raw
        if (LinkClassifier.isShortener(url) || LinkClassifier.classify(url) == Source.UNKNOWN) {
            url = runCatching { Http.resolveFinalUrl(url, Http.UA_MOBILE) }
                .onFailure { Log.w(TAG, "shortener resolve failed for $raw: ${it.message}") }
                .getOrDefault(url)
        }
        return when (LinkClassifier.classify(url)) {
            Source.TWITTER -> TwitterResolver.resolve(url)
            Source.TIKTOK -> TikTokResolver.resolve(url)
            else -> GenericResolver.resolve(url)
        }
    }
}
