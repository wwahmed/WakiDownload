package dev.wakilabs.wakidownload.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dev.wakilabs.wakidownload.BuildConfig
import dev.wakilabs.wakidownload.core.CloudException
import dev.wakilabs.wakidownload.core.Http
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OneDrive through Microsoft identity (personal and work accounts, `common` tenant) with a plain
 * OAuth 2.0 authorization-code + PKCE flow in the browser. No MSAL, no secret: the app is a public
 * client whose redirect URI `wakidownload://oauth/microsoft` is registered in Entra
 * (docs/CLOUD_SETUP.md). Tokens live in app-private preferences; the refresh token is rotated on use.
 *
 * Personal Vault: Microsoft Graph cannot read or write it, so the NSFW target is a normal OneDrive
 * folder (Settings > OneDrive NSFW folder). Moving files into the Vault is a OneDrive-app action.
 */
object OneDrive {
    private const val TAG = "WakiDownload"
    private const val AUTHORITY = "https://login.microsoftonline.com/common/oauth2/v2.0"
    private const val GRAPH = "https://graph.microsoft.com/v1.0"
    private const val SCOPES = "Files.ReadWrite offline_access User.Read"
    private const val PREFS = "onedrive"
    private const val SIMPLE_UPLOAD_LIMIT = 4L * 1024 * 1024
    private const val CHUNK = 10L * 1024 * 1024  // multiple of 320 KiB as Graph requires

    val configured: Boolean get() = BuildConfig.MS_CLIENT_ID.isNotBlank()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Opens the Microsoft sign-in page; the redirect comes back through [MicrosoftAuthActivity]. */
    fun beginAuth(context: Context): Intent {
        if (!configured) throw CloudException("OneDrive is not configured in this build (see docs/CLOUD_SETUP.md)")
        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(16)
        prefs(context).edit().putString("pkce.verifier", verifier).putString("pkce.state", state).apply()
        val url = Uri.parse("$AUTHORITY/authorize").buildUpon()
            .appendQueryParameter("client_id", BuildConfig.MS_CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", BuildConfig.MS_REDIRECT_URI)
            .appendQueryParameter("response_mode", "query")
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("prompt", "select_account")
            .build()
        return Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Exchanges the redirect's code for tokens and records the account name. Returns the account label. */
    fun completeAuth(context: Context, redirect: Uri): String {
        val p = prefs(context)
        val state = redirect.getQueryParameter("state")
        if (state == null || state != p.getString("pkce.state", null)) throw CloudException("Sign-in state mismatch; try again")
        redirect.getQueryParameter("error")?.let { throw CloudException("Microsoft sign-in failed: $it ${redirect.getQueryParameter("error_description") ?: ""}") }
        val code = redirect.getQueryParameter("code") ?: throw CloudException("No code in Microsoft redirect")
        val verifier = p.getString("pkce.verifier", null) ?: throw CloudException("Sign-in session expired; try again")
        val body = FormBody.Builder()
            .add("client_id", BuildConfig.MS_CLIENT_ID).add("grant_type", "authorization_code").add("code", code)
            .add("redirect_uri", BuildConfig.MS_REDIRECT_URI).add("code_verifier", verifier).add("scope", SCOPES).build()
        storeTokens(context, tokenCall(body))
        p.edit().remove("pkce.verifier").remove("pkce.state").apply()
        val me = JSONObject(get(accessToken(context), "$GRAPH/me?\$select=userPrincipalName,displayName,mail"))
        return me.optString("mail").ifBlank { me.optString("userPrincipalName").ifBlank { me.optString("displayName").ifBlank { "Microsoft account" } } }
    }

    fun signOut(context: Context) = prefs(context).edit().clear().apply()

    fun accessToken(context: Context): String {
        val p = prefs(context)
        val token = p.getString("access", null)
        val exp = p.getLong("expires", 0)
        if (token != null && System.currentTimeMillis() < exp - 60_000) return token
        val refresh = p.getString("refresh", null) ?: throw CloudException("Connect OneDrive in WakiDownload first")
        val body = FormBody.Builder()
            .add("client_id", BuildConfig.MS_CLIENT_ID).add("grant_type", "refresh_token").add("refresh_token", refresh).add("scope", SCOPES).build()
        storeTokens(context, tokenCall(body))
        return p.getString("access", null) ?: throw CloudException("OneDrive refresh gave no token")
    }

    private fun tokenCall(body: FormBody): JSONObject {
        val req = Request.Builder().url("$AUTHORITY/token").post(body).build()
        Http.client.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: "{}"
            val j = JSONObject(text)
            if (!r.isSuccessful || !j.has("access_token")) throw CloudException("Microsoft token error: ${j.optString("error")} ${j.optString("error_description").take(160)}")
            return j
        }
    }

    private fun storeTokens(context: Context, j: JSONObject) {
        val e = prefs(context).edit().putString("access", j.getString("access_token"))
            .putLong("expires", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000)
        if (j.has("refresh_token")) e.putString("refresh", j.getString("refresh_token"))
        e.apply()
    }

    /** Creates each missing segment of `A/B/C` under the drive root. */
    fun ensureFolder(token: String, path: String) {
        var parentPath = ""
        path.split('/').filter { it.isNotBlank() }.forEach { seg ->
            val full = if (parentPath.isEmpty()) seg else "$parentPath/$seg"
            val probe = Request.Builder().url("$GRAPH/me/drive/root:/${enc(full)}").header("Authorization", "Bearer $token").build()
            val exists = Http.client.newCall(probe).execute().use { it.isSuccessful }
            if (!exists) {
                val parentUrl = if (parentPath.isEmpty()) "$GRAPH/me/drive/root/children" else "$GRAPH/me/drive/root:/${enc(parentPath)}:/children"
                val body = JSONObject().put("name", seg).put("folder", JSONObject()).put("@microsoft.graph.conflictBehavior", "fail").toString()
                val req = Request.Builder().url(parentUrl).header("Authorization", "Bearer $token").post(body.toRequestBody("application/json".toMediaType())).build()
                Http.client.newCall(req).execute().use { r -> if (!r.isSuccessful && r.code != 409) throw CloudException("OneDrive could not create folder $full (${r.code})") }
            }
            parentPath = full
        }
    }

    /** Small files in one PUT, larger ones through an upload session. Returns the item's web link. */
    fun upload(token: String, folder: String, name: String, mime: String, file: File, onProgress: (Int) -> Unit): String {
        val itemPath = enc(if (folder.isBlank()) name else "$folder/$name")
        if (file.length() <= SIMPLE_UPLOAD_LIMIT) {
            val req = Request.Builder().url("$GRAPH/me/drive/root:/$itemPath:/content").header("Authorization", "Bearer $token")
                .put(GoogleDrive.progressBody(file, mime, onProgress)).build()
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw CloudException("OneDrive upload failed (${r.code}): ${r.body?.string()?.take(200)}")
                return JSONObject(r.body?.string() ?: "{}").optString("webUrl").ifBlank { "https://onedrive.live.com/" }
            }
        }
        val start = Request.Builder().url("$GRAPH/me/drive/root:/$itemPath:/createUploadSession").header("Authorization", "Bearer $token")
            .post(JSONObject().put("item", JSONObject().put("@microsoft.graph.conflictBehavior", "rename")).toString().toRequestBody("application/json".toMediaType())).build()
        val session = Http.client.newCall(start).execute().use { r ->
            if (!r.isSuccessful) throw CloudException("OneDrive refused the upload session (${r.code})")
            JSONObject(r.body?.string() ?: "{}").optString("uploadUrl").ifBlank { throw CloudException("OneDrive gave no upload URL") }
        }
        val total = file.length()
        var offset = 0L
        var last = JSONObject()
        RandomAccessFile(file, "r").use { raf ->
            while (offset < total) {
                val len = minOf(CHUNK, total - offset)
                val buf = ByteArray(len.toInt())
                raf.seek(offset); raf.readFully(buf)
                val req = Request.Builder().url(session)
                    .header("Content-Range", "bytes $offset-${offset + len - 1}/$total")
                    .put(buf.toRequestBody("application/octet-stream".toMediaType())).build()
                Http.client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw CloudException("OneDrive chunk failed (${r.code}) at $offset")
                    last = JSONObject(r.body?.string() ?: "{}")
                }
                offset += len
                onProgress(((offset * 100) / total).toInt())
            }
        }
        return last.optString("webUrl").ifBlank { "https://onedrive.live.com/" }
    }

    private fun get(token: String, url: String): String = Http.getString(url, mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"))

    private fun enc(path: String): String = path.split('/').joinToString("/") { Uri.encode(it) }

    internal fun challenge(verifier: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes); SecureRandom().nextBytes(b)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
}
