package dev.wakilabs.wakidownload.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.InputStream

/** Writes into `Downloads/WakiDownload/` through MediaStore, so no storage permission is needed on API 29+. */
object MediaStoreWriter {
    const val FOLDER = "WakiDownload"
    val relativePath: String get() = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER

    /**
     * Streams [input] into a new pending row, then publishes it. Returns the row Uri and bytes written.
     * On any failure the pending row is deleted so no zero-byte ghost shows up in Files.
     */
    fun write(
        context: Context,
        displayName: String,
        mime: String,
        input: InputStream,
        totalBytes: Long,
        relativePathOverride: String? = null,
        onProgress: (written: Long, total: Long) -> Unit,
    ): Pair<Uri, Long> {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePathOverride ?: relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore refused to create $displayName")
        var written = 0L
        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                val buf = ByteArray(1 shl 16)
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    if (written - lastReport >= (1 shl 18)) { lastReport = written; onProgress(written, totalBytes) }
                }
                out.flush()
            } ?: throw IllegalStateException("Could not open $displayName for writing")
            if (written == 0L) throw IllegalStateException("Server sent no data for $displayName")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            onProgress(written, totalBytes)
            return uri to written
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }
}
