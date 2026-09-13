package dev.wakilabs.wakidownload.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import dev.wakilabs.wakidownload.BuildConfig
import dev.wakilabs.wakidownload.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class UpdateManifest(
    val version: String,
    val versionCode: Long,
    val apkUrl: String,
    val apkSha256: String,
    val signingCertSha256: String,
    val byteSize: Long,
    val releaseNotes: String,
)

class UpdateException(message: String) : Exception(message)

/**
 * GitHub Releases is the source of truth. publish.sh uploads `latest.json` next to the APK on
 * every release; the app reads it through the stable `releases/latest/download/latest.json`
 * URL (the repo is public, so no credential is needed and nothing is embedded in the APK).
 *
 * Before PackageInstaller sees a byte, the download is checked for: file SHA-256 (manifest),
 * package name, versionCode strictly newer than installed AND equal to the manifest's, and the
 * FULL signing-certificate SHA-256 against the build-time pin. A keystore swap therefore cannot
 * be pushed to an installed phone; the pin is the app's identity.
 */
object Updater {
    private const val TAG = "WakiDownload"

    fun installedVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return info.longVersionCode
    }

    suspend fun fetchManifest(): UpdateManifest = withContext(Dispatchers.IO) {
        val body = Http.getString(BuildConfig.UPDATE_MANIFEST_URL, mapOf("User-Agent" to "WakiDownload/${BuildConfig.VERSION_NAME}", "Accept" to "application/json", "Cache-Control" to "no-cache"))
        val j = JSONObject(body)
        UpdateManifest(
            version = j.getString("version"),
            versionCode = j.getLong("versionCode"),
            apkUrl = j.getString("apkUrl"),
            apkSha256 = j.getString("apkSha256").lowercase(),
            signingCertSha256 = j.optString("signingCertSha256").lowercase(),
            byteSize = j.optLong("byteSize", -1),
            releaseNotes = j.optString("releaseNotes"),
        )
    }

    /** Null when the installed build is already the newest. */
    suspend fun checkForUpdate(context: Context): UpdateManifest? {
        val m = fetchManifest()
        return if (m.versionCode > installedVersionCode(context)) m else null
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun download(context: Context, manifest: UpdateManifest, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "WakiDownload-${manifest.version}.apk")
        val req = Request.Builder().url(manifest.apkUrl).header("User-Agent", "WakiDownload/${BuildConfig.VERSION_NAME}").build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw UpdateException("HTTP ${resp.code} downloading update")
            val body = resp.body ?: throw UpdateException("Empty update download")
            val total = if (body.contentLength() > 0) body.contentLength() else manifest.byteSize
            file.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var written = 0L
                var last = -1
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        val pct = if (total > 0) ((written * 100) / total).toInt() else -1
                        if (pct != last) { last = pct; onProgress(pct) }
                    }
                }
            }
        }
        file
    }

    /** Throws [UpdateException] naming the failed check; returns silently when every check passes. */
    fun verify(context: Context, file: File, manifest: UpdateManifest) {
        if (!file.exists() || file.length() == 0L) throw UpdateException("update file missing")
        if (manifest.byteSize > 0 && file.length() != manifest.byteSize) throw UpdateException("size mismatch (${file.length()} vs ${manifest.byteSize})")

        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != manifest.apkSha256) throw UpdateException("sha256 mismatch")

        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw UpdateException("unreadable APK")
        if (archive.packageName != context.packageName) throw UpdateException("package mismatch (${archive.packageName})")
        val archiveCode = archive.longVersionCode
        if (archiveCode <= installedVersionCode(context)) throw UpdateException("not newer (code $archiveCode)")
        if (archiveCode != manifest.versionCode) throw UpdateException("versionCode differs from manifest ($archiveCode vs ${manifest.versionCode})")

        val certBytes = archive.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: throw UpdateException("unsigned APK")
        val certSha = MessageDigest.getInstance("SHA-256").digest(certBytes).joinToString("") { "%02x".format(it) }
        if (certSha != BuildConfig.RELEASE_CERT_SHA256) throw UpdateException("signing certificate mismatch")
        if (manifest.signingCertSha256.isNotBlank() && manifest.signingCertSha256 != certSha) throw UpdateException("manifest certificate mismatch")
        Log.i(TAG, "update ${manifest.version} (code $archiveCode) verified: sha ok, cert ok")
    }

    /** Hands the verified APK to PackageInstaller; the OS confirmation arrives via [InstallReceiver]. */
    fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("WakiDownload.apk", 0, file.length()).use { out ->
                    FileInputStream(file).use { it.copyTo(out, 1 shl 16) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallReceiver::class.java).setAction(InstallReceiver.ACTION_STATUS)
                val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
            }
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw t
        }
    }
}
