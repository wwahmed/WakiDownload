package dev.wakilabs.wakidownload.core

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.wakilabs.wakidownload.R
import dev.wakilabs.wakidownload.resolve.Resolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.UUID

/**
 * One job: a shared link (resolve, download) or files already staged in the cache (shared from
 * another app), then save every item into the chosen target. Expedited work with a data-sync
 * foreground notification so a long video survives the share card closing.
 */
class DownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val notifId = id.hashCode() and 0x7fffffff
        val settings = Settings(applicationContext)
        val nsfw = inputData.getBoolean(KEY_NSFW, false)
        runCatching { setForeground(foreground(notifId, "WakiDownload", applicationContext.getString(R.string.share_resolving), 0, 0, true)) }
            .onFailure { Log.w(TAG, "foreground refused: ${it.message}") }
        setProgress(workDataOf(KEY_STAGE to STAGE_RESOLVING))
        val staging = File(applicationContext.cacheDir, "staging/$id").apply { mkdirs() }

        return try {
            val text = inputData.getString(KEY_TEXT)
            val stagedPaths = inputData.getStringArray(KEY_FILES)
            val plan: List<Planned> = if (stagedPaths != null) {
                val mimes = inputData.getStringArray(KEY_MIMES) ?: emptyArray()
                stagedPaths.mapIndexed { i, p -> Planned(File(p), mimes.getOrElse(i) { "application/octet-stream" }, Source.SHARED) }
            } else {
                val resolved = withContext(Dispatchers.IO) { Resolver.resolve(text ?: throw ResolveException("Nothing to download")) }
                resolved.items.mapIndexed { i, item ->
                    val name = FileNames.build(resolved.source, resolved.id, item.ext, i, resolved.items.size)
                    Planned(File(staging, name), item.mime, resolved.source, item)
                }
            }
            val source = plan.first().source
            val target = settings.target(inputData.getString(KEY_TARGET) ?: if (nsfw) settings.nsfwTargetId else settings.defaultTargetId(source))

            val saved = mutableListOf<SavedFile>()
            plan.forEachIndexed { index, p ->
                val name = p.file.name
                if (p.item != null) {
                    progress(STAGE_DOWNLOADING, name, index, plan.size, -1)
                    withContext(Dispatchers.IO) { fetch(p.item, p.file) { pct -> progress(STAGE_DOWNLOADING, name, index, plan.size, pct, notifId) } }
                }
                progress(STAGE_SAVING, name, index, plan.size, -1, notifId, target.name)
                val out = withContext(Dispatchers.IO) {
                    TargetWriter.save(applicationContext, settings, target, nsfw, p.file, name, p.mime) { pct -> progress(STAGE_SAVING, name, index, plan.size, pct, notifId, target.name) }
                }
                saved += out
                p.file.delete()
            }
            DownloadHistory(applicationContext).add(saved.map { HistoryEntry(it.name, it.uri, it.mime, source.slug, kindOf(it.mime), it.bytes, System.currentTimeMillis(), it.target.name, nsfw) })
            Notifications.completed(applicationContext, notifId, saved.map { it.name }, Uri.parse(saved.first().uri), saved.first().mime, target)
            Result.success(workDataOf(KEY_NAMES to saved.map { it.name }.toTypedArray(), KEY_COUNT to saved.size, KEY_FIRST_URI to saved.first().uri, KEY_TARGET to target.name))
        } catch (t: Throwable) {
            val msg = t.message ?: t.javaClass.simpleName
            Log.e(TAG, "download failed: $msg", t)
            Notifications.failed(applicationContext, notifId, msg)
            Result.failure(workDataOf(KEY_ERROR to msg))
        } finally {
            staging.deleteRecursively()
        }
    }

    private class Planned(val file: File, val mime: String, val source: Source, val item: MediaItem? = null)

    private fun kindOf(mime: String) = when { mime.startsWith("image/gif") -> "gif"; mime.startsWith("image/") -> "image"; else -> "video" }

    private fun fetch(item: MediaItem, into: File, onProgress: (Int) -> Unit) {
        val req = Request.Builder().url(item.url).get()
        item.headers.forEach { (k, v) -> if (v.isNotBlank()) req.header(k, v) }
        Http.client.newCall(req.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw ResolveException("Media server answered HTTP ${resp.code}")
            val body = resp.body ?: throw ResolveException("Media server sent an empty body")
            val total = body.contentLength()
            into.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    var written = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); written += n
                        val pct = if (total > 0) ((written * 100) / total).toInt() else -1
                        if (pct != last) { last = pct; onProgress(pct) }
                    }
                }
            }
            if (into.length() == 0L) throw ResolveException("Server sent no data")
        }
    }

    private fun progress(stage: String, name: String, index: Int, count: Int, pct: Int, notifId: Int = -1, targetName: String = "") {
        val label = (if (count > 1) "$name (${index + 1}/$count)" else name) + if (targetName.isNotBlank()) " to $targetName" else ""
        setProgressAsync(workDataOf(KEY_STAGE to stage, KEY_NAME to name, KEY_INDEX to index, KEY_COUNT to count, KEY_PERCENT to pct, KEY_TARGET to targetName))
        if (notifId >= 0) runCatching {
            val title = if (stage == STAGE_SAVING) "Saving" else "Downloading"
            setForegroundAsync(foreground(notifId, title, label, 100, pct.coerceAtLeast(0), pct < 0))
        }
    }

    private fun foreground(id: Int, title: String, text: String?, max: Int, progress: Int, indeterminate: Boolean): ForegroundInfo {
        val n = Notifications.progress(applicationContext, title, text, max, progress, indeterminate)
        return ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(id.hashCode() and 0x7fffffff, "WakiDownload", null, 0, 0, true)

    companion object {
        const val TAG = Resolver.TAG
        const val KEY_TEXT = "text"
        const val KEY_FILES = "files"
        const val KEY_MIMES = "mimes"
        const val KEY_TARGET = "target"
        const val KEY_NSFW = "nsfw"
        const val KEY_STAGE = "stage"
        const val KEY_NAME = "name"
        const val KEY_NAMES = "names"
        const val KEY_INDEX = "index"
        const val KEY_COUNT = "count"
        const val KEY_PERCENT = "percent"
        const val KEY_ERROR = "error"
        const val KEY_FIRST_URI = "firstUri"
        const val STAGE_RESOLVING = "resolving"
        const val STAGE_DOWNLOADING = "downloading"
        const val STAGE_SAVING = "saving"

        fun enqueueLink(context: Context, sharedText: String, targetId: String, nsfw: Boolean): UUID =
            enqueue(context, Data.Builder().putString(KEY_TEXT, sharedText).putString(KEY_TARGET, targetId).putBoolean(KEY_NSFW, nsfw).build())

        fun enqueueFiles(context: Context, files: List<File>, mimes: List<String>, targetId: String, nsfw: Boolean): UUID =
            enqueue(context, Data.Builder().putStringArray(KEY_FILES, files.map { it.absolutePath }.toTypedArray()).putStringArray(KEY_MIMES, mimes.toTypedArray()).putString(KEY_TARGET, targetId).putBoolean(KEY_NSFW, nsfw).build())

        private fun enqueue(context: Context, data: Data): UUID {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).addTag("download").build()
            WorkManager.getInstance(context).enqueue(request)
            return request.id
        }
    }
}
