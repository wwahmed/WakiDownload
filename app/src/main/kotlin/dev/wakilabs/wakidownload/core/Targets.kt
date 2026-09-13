package dev.wakilabs.wakidownload.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class TargetKind { LOCAL, GDRIVE, ONEDRIVE, SAF }

/** Where a download can land. LOCAL is always present; the rest appear once connected / picked. */
data class Target(val id: String, val kind: TargetKind, val name: String, val treeUri: String? = null) {
    val isCloud: Boolean get() = kind == TargetKind.GDRIVE || kind == TargetKind.ONEDRIVE

    companion object {
        const val LOCAL_ID = "local"
        const val GDRIVE_ID = "gdrive"
        const val ONEDRIVE_ID = "onedrive"
        val LOCAL = Target(LOCAL_ID, TargetKind.LOCAL, "Downloads / WakiDownload")
    }
}

class CloudException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * All user settings, in one SharedPreferences file. Per-source default targets, the NSFW target,
 * connected cloud accounts, custom SAF folders, and the ask-each-time switch.
 */
class Settings(context: Context) {
    private val p: SharedPreferences = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // Cloud accounts (the tokens live elsewhere; this is the display state)
    var gdriveAccount: String?
        get() = p.getString("gdrive.account", null)
        set(v) = p.edit().putString("gdrive.account", v).apply()
    var gdriveFolder: String
        get() = p.getString("gdrive.folder", "WakiDownload")!!
        set(v) = p.edit().putString("gdrive.folder", v.trim('/').ifBlank { "WakiDownload" }).apply()
    var onedriveAccount: String?
        get() = p.getString("onedrive.account", null)
        set(v) = p.edit().putString("onedrive.account", v).apply()
    var onedriveFolder: String
        get() = p.getString("onedrive.folder", "WakiDownload")!!
        set(v) = p.edit().putString("onedrive.folder", v.trim('/').ifBlank { "WakiDownload" }).apply()
    var nsfwSubfolder: String
        get() = p.getString("nsfw.subfolder", "NSFW")!!
        set(v) = p.edit().putString("nsfw.subfolder", v.trim('/').ifBlank { "NSFW" }).apply()
    var askEachTime: Boolean
        get() = p.getBoolean("askEachTime", true)
        set(v) = p.edit().putBoolean("askEachTime", v).apply()

    // Custom folders picked through the system picker (any DocumentsProvider: Files, Drive app, OneDrive app)
    fun safTargets(): List<Target> = runCatching {
        val arr = JSONArray(p.getString("saf.targets", "[]"))
        (0 until arr.length()).map { i -> val o = arr.getJSONObject(i); Target(o.getString("id"), TargetKind.SAF, o.getString("name"), o.getString("uri")) }
    }.getOrDefault(emptyList())

    fun addSafTarget(name: String, treeUri: String): Target {
        val t = Target("saf:" + UUID.randomUUID().toString().take(8), TargetKind.SAF, name, treeUri)
        writeSaf(safTargets() + t)
        return t
    }

    fun renameSafTarget(id: String, name: String) = writeSaf(safTargets().map { if (it.id == id) it.copy(name = name) else it })

    fun removeSafTarget(id: String) {
        writeSaf(safTargets().filterNot { it.id == id })
        // any default that pointed here falls back to local
        listOf("twitter", "tiktok", "web", "shared", "nsfw").forEach { k -> if (p.getString("default.$k", null) == id) p.edit().remove("default.$k").apply() }
    }

    private fun writeSaf(list: List<Target>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("uri", it.treeUri)) }
        p.edit().putString("saf.targets", arr.toString()).apply()
    }

    /** Targets that can take a file right now (cloud ones only when connected). */
    fun availableTargets(): List<Target> {
        val out = mutableListOf(Target.LOCAL)
        if (gdriveAccount != null) out += Target(Target.GDRIVE_ID, TargetKind.GDRIVE, "Google Drive / $gdriveFolder")
        if (onedriveAccount != null) out += Target(Target.ONEDRIVE_ID, TargetKind.ONEDRIVE, "OneDrive / $onedriveFolder")
        out += safTargets()
        return out
    }

    fun target(id: String?): Target = availableTargets().firstOrNull { it.id == id } ?: Target.LOCAL

    /** Per-source default. Keys: twitter, tiktok, web (other links), shared (files shared from apps). */
    fun defaultTargetId(source: Source): String = p.getString("default.${defaultKey(source)}", Target.LOCAL_ID) ?: Target.LOCAL_ID
    fun setDefaultTargetId(source: Source, id: String) = p.edit().putString("default.${defaultKey(source)}", id).apply()

    /** Where NSFW downloads go, whatever the source. Defaults to OneDrive once connected, else local. */
    var nsfwTargetId: String
        get() = p.getString("default.nsfw", null) ?: if (onedriveAccount != null) Target.ONEDRIVE_ID else Target.LOCAL_ID
        set(v) = p.edit().putString("default.nsfw", v).apply()

    private fun defaultKey(source: Source) = when (source) {
        Source.TWITTER -> "twitter"
        Source.TIKTOK -> "tiktok"
        Source.SHARED -> "shared"
        else -> "web"
    }

    /** Folder path inside the target. LOCAL: relative to the volume root; cloud: relative to the drive root. */
    fun folderPath(target: Target, nsfw: Boolean): String {
        val base = when (target.kind) {
            TargetKind.LOCAL -> "Download/${MediaStoreWriter.FOLDER}"
            TargetKind.GDRIVE -> gdriveFolder
            TargetKind.ONEDRIVE -> onedriveFolder
            TargetKind.SAF -> ""
        }
        if (target.kind == TargetKind.SAF) return ""
        return if (nsfw) "$base/$nsfwSubfolder" else base
    }
}
