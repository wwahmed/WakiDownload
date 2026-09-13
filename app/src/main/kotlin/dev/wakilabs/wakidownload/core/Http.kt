package dev.wakilabs.wakidownload.core

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object Http {
    const val UA_DESKTOP = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    const val UA_MOBILE = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    const val UA_TIKTOK_APP = "com.zhiliaoapp.musically/2023501030 (Linux; U; Android 13; en_US; Pixel 7; Build/TQ3A.230901.001; Cronet/TTNetVersion:3d2d3f2d 2023-08-24 QuicVersion:47946d2a 2023-06-20)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** A client that remembers cookies for one resolution (TikTok's CDN rejects requests without the page's cookies). */
    fun cookieClient(): Pair<OkHttpClient, MemoryCookieJar> {
        val jar = MemoryCookieJar()
        return client.newBuilder().cookieJar(jar).build() to jar
    }

    /** Follow redirects (t.co, vm.tiktok.com) and return where the link actually lands. */
    fun resolveFinalUrl(url: String, userAgent: String, client: OkHttpClient = this.client): String {
        val req = Request.Builder().url(url).header("User-Agent", userAgent).get().build()
        client.newCall(req).execute().use { resp -> return resp.request.url.toString() }
    }

    fun getString(url: String, headers: Map<String, String>, client: OkHttpClient = this.client, timeoutSeconds: Long? = null): String {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        val c = if (timeoutSeconds != null) client.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build() else client
        c.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw ResolveException("HTTP ${resp.code} for $url")
            return resp.body?.string() ?: throw ResolveException("Empty body for $url")
        }
    }
}

class MemoryCookieJar : CookieJar {
    private val store = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { c -> store.removeAll { it.name == c.name && it.domain == c.domain }; store.add(c) }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> = store.filter { it.matches(url) }

    /** `Cookie:` header value for a host, so a plain client can replay what the page handed out. */
    @Synchronized
    fun headerFor(host: String): String =
        store.filter { host == it.domain || host.endsWith("." + it.domain) }.joinToString("; ") { "${it.name}=${it.value}" }
}
