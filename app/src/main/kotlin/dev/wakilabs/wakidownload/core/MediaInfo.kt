package dev.wakilabs.wakidownload.core

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Lazily read facts about a saved file (dimensions, duration, codec). Cached per Uri for the process lifetime. */
data class MediaFacts(val width: Int, val height: Int, val durationMs: Long, val mime: String?, val bitrate: Int)

object MediaInfo {
    private val cache = ConcurrentHashMap<String, MediaFacts>()

    fun cached(uri: String): MediaFacts? = cache[uri]

    suspend fun facts(context: Context, uri: String, mime: String): MediaFacts? = withContext(Dispatchers.IO) {
        cache[uri]?.let { return@withContext it }
        val u = Uri.parse(uri)
        if (u.scheme == "https" || u.scheme == "http") return@withContext null
        val facts = runCatching {
            if (mime.startsWith("video/")) {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, u)
                    val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val m = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    val b = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
                    MediaFacts(w, h, d, m, b)
                }
            } else {
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
                MediaFacts(opts.outWidth, opts.outHeight, 0, opts.outMimeType, 0)
            }
        }.getOrNull()
        if (facts != null) cache[uri] = facts
        facts
    }

    /** A thumbnail for local and SAF files; null for cloud links (Glide handles the rest). */
    suspend fun thumbnail(context: Context, uri: String, size: Size = Size(512, 512)): Bitmap? = withContext(Dispatchers.IO) {
        val u = Uri.parse(uri)
        if (u.scheme != "content") return@withContext null
        runCatching { context.contentResolver.loadThumbnail(u, size, null) }.getOrNull()
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0) return ""
        val s = ms / 1000
        val m = s / 60
        val h = m / 60
        return if (h > 0) "%d:%02d:%02d".format(h, m % 60, s % 60) else "%d:%02d".format(m, s % 60)
    }
}
