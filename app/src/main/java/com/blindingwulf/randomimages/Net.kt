package com.blindingwulf.randomimages

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * User-Agent strings and request helpers.
 *
 * The e621 403-without-UA behaviour was re-verified against the live API on
 * 2026-11-02 (no UA -> HTTP 403), so every request here sets one explicitly:
 * HttpURLConnection otherwise sends Dalvik's default, which e621 rejects.
 */
object Net {
    const val E621_UA = "RandomImages/1.0 (personal android client; single user)"
    const val FA_UA =
        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"
    const val REFERER_E621 = "https://e621.net/"
    const val REFERER_FA = "https://www.furaffinity.net/"

    class HttpException(val status: Int, message: String) : Exception(message)

    private fun open(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int,
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.requestMethod = "GET"
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return conn
    }

    fun getString(
        baseUrl: String,
        params: Map<String, String>,
        headers: Map<String, String>,
        timeoutMs: Int = 25_000,
    ): String {
        val url = buildString {
            append(baseUrl)
            if (params.isNotEmpty()) {
                append('?')
                append(
                    params.entries.joinToString("&") { (k, v) ->
                        URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
                    }
                )
            }
        }
        val conn = open(url, headers, timeoutMs)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw HttpException(code, "HTTP $code for $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Returns bytes, or throws [HttpException]/IOException on failure. */
    fun getBytes(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int = 30_000,
        minBytes: Int = 512,
    ): ByteArray {
        val conn = open(url, headers, timeoutMs)
        try {
            val code = conn.responseCode
            if (code == 404) throw HttpException(404, "404 for $url")
            if (code !in 200..299) throw HttpException(code, "HTTP $code for $url")
            val data = conn.inputStream.use { it.readBytes() }
            if (data.size < minBytes) {
                throw HttpException(code, "response too small (${data.size} bytes)")
            }
            return data
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Per-provider minimum spacing between requests, matching providers.py:
 * e621 allows 2 req/s (0.55 s used), FurAffinity asks for <= 1 req/s (1.1 s).
 */
class Throttle(private val minIntervalMs: Long) {
    private var lastRequestMs = 0L
    private val lock = Any()

    /** Blocks the calling thread until the next request is allowed. */
    fun await() {
        val waitMs = synchronized(lock) {
            val wait = minIntervalMs - (SystemClockCompat.elapsed() - lastRequestMs)
            if (wait > 0) wait else 0L
        }
        if (waitMs > 0) Thread.sleep(waitMs)
        synchronized(lock) { lastRequestMs = SystemClockCompat.elapsed() }
    }
}

private object SystemClockCompat {
    fun elapsed(): Long = android.os.SystemClock.elapsedRealtime()
}
