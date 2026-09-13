package dev.wakilabs.wakidownload.cloud

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import dev.wakilabs.wakidownload.core.CloudException
import dev.wakilabs.wakidownload.core.Http
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Google Drive through the device's signed-in Google account (Play Services AuthorizationClient).
 * Scope is `drive.file`: the app sees only files and folders it created. No client secret in the
 * APK; Google identifies the app by package name + signing certificate, registered in the
 * `waki-brain` Google Cloud project (see docs/CLOUD_SETUP.md).
 */
object GoogleDrive {
    private const val TAG = "WakiDownload"
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"

    fun request(): AuthorizationRequest = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Silent token for a previously granted account; [CloudException] if the user has to act first. */
    fun accessToken(context: Context): String {
        val result: AuthorizationResult = try {
            Tasks.await(Identity.getAuthorizationClient(context).authorize(request()), 60, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            throw CloudException("Google Drive sign-in failed: ${t.cause?.message ?: t.message}", t)
        }
        if (result.hasResolution()) throw CloudException("Connect Google Drive in WakiDownload first")
        return result.accessToken ?: throw CloudException("Google Drive gave no access token")
    }

    fun whoAmI(token: String): String {
        val body = get(token, "$API/about?fields=user(emailAddress,displayName)")
        val u = JSONObject(body).optJSONObject("user") ?: return "Google account"
        return u.optString("emailAddress").ifBlank { u.optString("displayName").ifBlank { "Google account" } }
    }

    /** Walks `A/B/C` from My Drive, creating what is missing; returns the leaf folder id. */
    fun ensureFolder(token: String, path: String): String {
        var parent = "root"
        path.split('/').filter { it.isNotBlank() }.forEach { seg ->
            val q = "name = '${seg.replace("'", "\\'")}' and mimeType = '$FOLDER_MIME' and '$parent' in parents and trashed = false"
            val found = JSONObject(get(token, "$API/files?spaces=drive&fields=files(id,name)&q=" + java.net.URLEncoder.encode(q, "UTF-8"))).optJSONArray("files") ?: JSONArray()
            parent = if (found.length() > 0) found.getJSONObject(0).getString("id") else {
                val created = post(token, "$API/files?fields=id", JSONObject().put("name", seg).put("mimeType", FOLDER_MIME).put("parents", JSONArray().put(parent)).toString())
                JSONObject(created).getString("id")
            }
        }
        return parent
    }

    /** Resumable upload in one PUT; returns the file's web link. */
    fun upload(token: String, folderId: String, name: String, mime: String, file: File, onProgress: (Int) -> Unit): String {
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(folderId)).toString()
        val start = Request.Builder().url(UPLOAD)
            .header("Authorization", "Bearer $token")
            .header("X-Upload-Content-Type", mime)
            .header("X-Upload-Content-Length", file.length().toString())
            .post(meta.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()
        val session = Http.client.newCall(start).execute().use { r ->
            if (!r.isSuccessful) throw CloudException("Drive refused the upload (${r.code}): ${r.body?.string()?.take(200)}")
            r.header("Location") ?: throw CloudException("Drive gave no upload session")
        }
        val put = Request.Builder().url(session).header("Authorization", "Bearer $token").put(progressBody(file, mime, onProgress)).build()
        val id = Http.client.newBuilder().writeTimeout(0, TimeUnit.MILLISECONDS).build().newCall(put).execute().use { r ->
            if (!r.isSuccessful) throw CloudException("Drive upload failed (${r.code}): ${r.body?.string()?.take(200)}")
            JSONObject(r.body?.string() ?: "{}").optString("id")
        }
        if (id.isBlank()) throw CloudException("Drive upload returned no file id")
        val link = runCatching { JSONObject(get(token, "$API/files/$id?fields=webViewLink")).optString("webViewLink") }.getOrDefault("")
        return link.ifBlank { "https://drive.google.com/file/d/$id/view" }
    }

    private fun get(token: String, url: String): String = Http.getString(url, mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"))

    private fun post(token: String, url: String, json: String): String {
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").post(json.toRequestBody("application/json; charset=UTF-8".toMediaType())).build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw CloudException("Drive API error ${r.code}: ${r.body?.string()?.take(200)}")
            return r.body?.string() ?: "{}"
        }
    }

    internal fun progressBody(file: File, mime: String, onProgress: (Int) -> Unit): RequestBody = object : RequestBody() {
        override fun contentType() = mime.toMediaType()
        override fun contentLength() = file.length()
        override fun writeTo(sink: BufferedSink) {
            val total = file.length()
            var written = 0L
            var last = -1
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    sink.write(buf, 0, n); written += n
                    val pct = if (total > 0) ((written * 100) / total).toInt() else -1
                    if (pct != last) { last = pct; onProgress(pct) }
                }
            }
        }
    }
}
