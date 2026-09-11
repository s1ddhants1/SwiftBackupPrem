package io.github.s1ddhants1.swiftbackupprem.hook.experimental.cloudproviders

import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.Keep
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.AppUtils
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

@Keep
object CloudHttpHelper {
    private const val TAG = Consts.TAG
    private const val DEFAULT_TIMEOUT = 15000

    fun SharedPreferences.findFirstString(vararg keys: String): String? {
        for (k in keys) {
            val v = getString(k, null)?.trim()
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    fun executeGet(
        urlStr: String,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
        maxRedirects: Int = 5,
        onAuthRedirect: ((String) -> Map<String, String>)? = null,
        on401Retry: (() -> Map<String, String>?)? = null
    ): String? = executeHttpWithRedirects(
        initialUrl = urlStr,
        method = "GET",
        initialHeaders = headers,
        followRedirects = followRedirects,
        maxRedirects = maxRedirects,
        onAuthRedirect = onAuthRedirect,
        on401Retry = on401Retry
    ) { it.bufferedReader(StandardCharsets.UTF_8).use { r -> r.readText() } }

    fun executeGetRange(
        urlStr: String,
        headers: Map<String, String> = emptyMap(),
        startByte: Long,
        endByte: Long,
        followRedirects: Boolean = true,
        maxRedirects: Int = 5,
        onAuthRedirect: ((String) -> Map<String, String>)? = null,
        on401Retry: (() -> Map<String, String>?)? = null
    ): ByteArray? {
        val rangeHeaders = headers.toMutableMap().apply {
            put("Range", "bytes=$startByte-$endByte")
        }
        return executeHttpWithRedirects(
            initialUrl = urlStr,
            method = "GET",
            initialHeaders = rangeHeaders,
            followRedirects = followRedirects,
            maxRedirects = maxRedirects,
            onAuthRedirect = onAuthRedirect,
            on401Retry = on401Retry
        ) { it.use { s -> s.readBytes() } }
    }

    fun executePost(
        urlStr: String,
        headers: Map<String, String> = emptyMap(),
        bodyBytes: ByteArray? = null,
        method: String = "POST"
    ): String? = attempt("Cloud HTTP $method", silent = true) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            connectTimeout = DEFAULT_TIMEOUT
            readTimeout = DEFAULT_TIMEOUT
            if (bodyBytes != null) {
                doOutput = true
            }
        }
        try {
            if (bodyBytes != null) {
                conn.outputStream.use { it.write(bodyBytes) }
            }
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } else {
                Log.w(TAG, "[CloudHttp] $method returned $code for ${AppUtils.sanitizeUrl(urlStr)}")
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun <T> executeHttpWithRedirects(
        initialUrl: String,
        method: String,
        initialHeaders: Map<String, String>,
        followRedirects: Boolean,
        maxRedirects: Int,
        onAuthRedirect: ((String) -> Map<String, String>)?,
        on401Retry: (() -> Map<String, String>?)?,
        responseParser: (InputStream) -> T
    ): T? = attempt("Cloud HTTP $method", silent = true) {
        var currentUrl = initialUrl
        var currentHeaders = initialHeaders
        var redirects = 0

        while (redirects < (if (followRedirects) maxRedirects else 1)) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = method
                currentHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                connectTimeout = DEFAULT_TIMEOUT
                readTimeout = DEFAULT_TIMEOUT
            }

            try {
                val code = conn.responseCode
                if (code in 200..299) {
                    return@attempt responseParser(conn.inputStream)
                }

                if (code == 401 && on401Retry != null) {
                    val freshHeaders = on401Retry()
                    if (freshHeaders != null) {
                        currentHeaders = freshHeaders
                        continue
                    }
                }

                if (followRedirects && code in listOf(301, 302, 303, 307, 308)) {
                    val location = conn.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        currentUrl = location
                        if (onAuthRedirect != null) {
                            currentHeaders = onAuthRedirect(location)
                        }
                        redirects++
                        continue
                    }
                }

                Log.w(TAG, "[CloudHttp] $method returned $code for ${AppUtils.sanitizeUrl(currentUrl)}")
                return@attempt null
            } finally {
                conn.disconnect()
            }
        }
        null
    }
}
