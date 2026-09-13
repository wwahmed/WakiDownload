package dev.wakilabs.wakidownload.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import dev.wakilabs.wakidownload.cloud.GoogleDrive
import dev.wakilabs.wakidownload.cloud.OneDrive
import java.io.File

/** Where a saved file ended up: a content Uri (local / SAF) or an https link (cloud). */
data class SavedFile(val name: String, val uri: String, val mime: String, val bytes: Long, val target: Target)

/** Puts a finished local file into the chosen target. Progress is 0..100 for the copy or upload. */
object TargetWriter {
    fun save(context: Context, settings: Settings, target: Target, nsfw: Boolean, file: File, name: String, mime: String, onProgress: (Int) -> Unit): SavedFile {
        val folder = settings.folderPath(target, nsfw)
        return when (target.kind) {
            TargetKind.LOCAL -> saveLocal(context, folder, file, name, mime, onProgress, target)
            TargetKind.SAF -> saveSaf(context, target, file, name, mime, onProgress)
            TargetKind.GDRIVE -> {
                val token = GoogleDrive.accessToken(context)
                val folderId = GoogleDrive.ensureFolder(token, folder)
                val link = GoogleDrive.upload(token, folderId, name, mime, file, onProgress)
                SavedFile(name, link, mime, file.length(), target)
            }
            TargetKind.ONEDRIVE -> {
                val token = OneDrive.accessToken(context)
                OneDrive.ensureFolder(token, folder)
                val link = OneDrive.upload(token, folder, name, mime, file, onProgress)
                SavedFile(name, link, mime, file.length(), target)
            }
        }
    }

    private fun saveLocal(context: Context, relativePath: String, file: File, name: String, mime: String, onProgress: (Int) -> Unit, target: Target): SavedFile {
        val total = file.length()
        val (uri, bytes) = file.inputStream().use { input ->
            MediaStoreWriter.write(context, name, mime, input, total, relativePath) { w, t -> if (t > 0) onProgress(((w * 100) / t).toInt()) }
        }
        return SavedFile(name, uri.toString(), mime, bytes, target)
    }

    private fun saveSaf(context: Context, target: Target, file: File, name: String, mime: String, onProgress: (Int) -> Unit): SavedFile {
        val tree = Uri.parse(target.treeUri ?: throw CloudException("Folder ${target.name} has no location"))
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val resolver = context.contentResolver
        val doc = runCatching { DocumentsContract.createDocument(resolver, dirUri, mime, name) }.getOrNull()
            ?: throw CloudException("Cannot create files in ${target.name}. Remove the folder and pick it again.")
        try {
            val total = file.length()
            resolver.openOutputStream(doc, "w")?.use { out ->
                file.inputStream().use { input ->
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
            } ?: throw CloudException("Cannot write to ${target.name}")
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw t
        }
        return SavedFile(name, doc.toString(), mime, file.length(), target)
    }

    /** Best-effort display name for a picked folder: provider brand + leaf folder. */
    fun describeTree(context: Context, tree: Uri): String {
        val brand = when (tree.authority) {
            "com.google.android.apps.docs.storage" -> "Google Drive"
            "com.microsoft.skydrive.content.StorageAccessProvider" -> "OneDrive"
            "com.android.externalstorage.documents" -> "Phone"
            "com.android.providers.downloads.documents" -> "Downloads"
            else -> tree.authority?.substringAfterLast('.')?.replaceFirstChar { it.uppercase() } ?: "Folder"
        }
        val docId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: ""
        val leaf = docId.substringAfterLast('/').substringAfterLast(':').ifBlank { "root" }
        val name = runCatching {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            context.contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: leaf
        return "$brand / $name"
    }
}
