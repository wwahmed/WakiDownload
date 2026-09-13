package dev.wakilabs.wakidownload.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** `<source>-<yyyymmdd-hhmmss>-<short-id>.<ext>`; multi-item posts add `-<n>` before the extension. */
object FileNames {
    fun build(source: Source, id: String, ext: String, index: Int, count: Int, at: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(at))
        val suffix = if (count > 1) "-${index + 1}" else ""
        return "${source.slug}-$stamp-${LinkClassifier.shortId(id)}$suffix.$ext"
    }

    fun extFromUrl(url: String, fallback: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "")
        return if (ext.length in 2..4 && ext.all { it.isLetterOrDigit() }) ext.lowercase() else fallback
    }

    fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        else -> "application/octet-stream"
    }
}
