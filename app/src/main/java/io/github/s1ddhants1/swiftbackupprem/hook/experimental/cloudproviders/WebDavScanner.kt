package io.github.s1ddhants1.swiftbackupprem.hook.experimental.cloudproviders

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.annotation.Keep
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.AppUtils
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.LinkedList
import java.util.Queue
import javax.xml.parsers.DocumentBuilderFactory

@Keep
object WebDavScanner : CloudScanner {
    private const val TAG = Consts.TAG
    override val providerName: String = "WebDAV"

    private const val PROPFIND_XML = """<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:displayname/>
    <d:getcontentlength/>
    <d:getlastmodified/>
    <d:resourcetype/>
  </d:prop>
</d:propfind>"""

    override fun isConfigured(context: Context, prefs: SharedPreferences): Boolean {
        val url = resolveWebDavUrl(prefs)
        return !url.isNullOrBlank()
    }

    fun resolveWebDavUrl(prefs: SharedPreferences): String? {
        val keys = listOf("webdav_url", "webdav_server_url", "webdav_endpoint", "nextcloud_url", "webdav_host", "webdav_cloud_url")
        for (k in keys) {
            val v = prefs.getString(k, null)
            if (!v.isNullOrBlank()) return v.trim()
        }
        return null
    }

    private fun resolveAuthHeader(prefs: SharedPreferences): String? {
        val userKeys = listOf("webdav_username", "webdav_user", "webdav_login", "nextcloud_user", "webdav_email")
        val passKeys = listOf("webdav_password", "webdav_pass", "webdav_token", "nextcloud_password", "webdav_app_password")

        var user: String? = null
        var pass: String? = null

        for (k in userKeys) {
            val v = prefs.getString(k, null)
            if (!v.isNullOrBlank()) { user = v.trim(); break }
        }
        for (k in passKeys) {
            val v = prefs.getString(k, null)
            if (!v.isNullOrBlank()) { pass = v.trim(); break }
        }

        if (user != null && pass != null) {
            val creds = "$user:$pass"
            val b64 = Base64.encodeToString(creds.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            return "Basic $b64"
        }

        val bearerToken = prefs.getString("webdav_bearer_token", null) ?: prefs.getString("nextcloud_token", null)
        if (!bearerToken.isNullOrBlank()) {
            return "Bearer ${bearerToken.trim()}"
        }

        return null
    }

    override fun listFiles(context: Context, prefs: SharedPreferences): List<CloudFileItem> {
        val baseUrl = resolveWebDavUrl(prefs) ?: return emptyList()
        val authHeader = resolveAuthHeader(prefs)
        val items = mutableListOf<CloudFileItem>()

        val queue: Queue<String> = LinkedList()
        val visited = mutableSetOf<String>()

        val normalizedBase = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        queue.add(normalizedBase)

        if (!normalizedBase.contains("SwiftBackup", ignoreCase = true)) {
            queue.add("${normalizedBase}SwiftBackup/")
        }

        var scannedFolders = 0
        while (queue.isNotEmpty() && scannedFolders < 50) {
            val currentUrl = queue.poll() ?: break
            if (!visited.add(currentUrl)) continue
            scannedFolders++

            val propfindResp = executePropfind(currentUrl, authHeader) ?: continue
            parsePropfindXml(currentUrl, propfindResp, queue, items)
        }

        Log.d(TAG, "[WebDavScanner] Discovered ${items.size} backup items across $scannedFolders WebDAV directories")
        return items
    }

    private fun parsePropfindXml(
        currentUrl: String,
        xmlText: String,
        dirQueue: Queue<String>,
        items: MutableList<CloudFileItem>
    ) {
        attempt("parse WebDAV PROPFIND XML", silent = true) {
            val dbFactory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }
            val dBuilder = dbFactory.newDocumentBuilder()
            val doc = dBuilder.parse(ByteArrayInputStream(xmlText.toByteArray(StandardCharsets.UTF_8)))
            doc.documentElement.normalize()

            val responses = doc.getElementsByTagNameNS("*", "response")
            for (i in 0 until responses.length) {
                val respElem = responses.item(i) as? Element ?: continue
                val hrefElem = respElem.getElementsByTagNameNS("*", "href").item(0) as? Element
                val hrefRaw = hrefElem?.textContent ?: continue
                val href = URLDecoder.decode(hrefRaw, "UTF-8")

                val isCollection = isCollectionResource(respElem)
                val contentLength = extractContentLength(respElem)

                val fullItemUrl = if (href.startsWith("http://") || href.startsWith("https://")) {
                    href
                } else {
                    val base = URL(currentUrl)
                    URL(base.protocol, base.host, base.port, href).toString()
                }

                val normalizedFull = if (isCollection && !fullItemUrl.endsWith("/")) "$fullItemUrl/" else fullItemUrl
                val normalizedCurrent = if (!currentUrl.endsWith("/")) "$currentUrl/" else currentUrl

                if (isCollection) {
                    if (normalizedFull != normalizedCurrent && !normalizedFull.equals(normalizedCurrent, ignoreCase = true)) {
                        dirQueue.add(normalizedFull)
                    }
                } else {
                    val fileName = href.substringAfterLast("/").takeIf { it.isNotBlank() }
                        ?: hrefRaw.substringAfterLast("/")
                    if (fileName.isNotBlank()) {
                        items.add(
                            CloudFileItem(
                                id = fullItemUrl,
                                name = fileName,
                                size = contentLength,
                                provider = providerName,
                                customDownloadUrl = fullItemUrl
                            )
                        )
                    }
                }
            }
        }
    }

    private fun isCollectionResource(respElem: Element): Boolean {
        val resTypeNodes = respElem.getElementsByTagNameNS("*", "resourcetype")
        if (resTypeNodes.length > 0) {
            val resTypeElem = resTypeNodes.item(0) as? Element
            if (resTypeElem != null) {
                val collections = resTypeElem.getElementsByTagNameNS("*", "collection")
                if (collections.length > 0) return true
            }
        }
        return false
    }

    private fun extractContentLength(respElem: Element): Long {
        val lengthNodes = respElem.getElementsByTagNameNS("*", "getcontentlength")
        if (lengthNodes.length > 0) {
            return lengthNodes.item(0)?.textContent?.trim()?.toLongOrNull() ?: 0L
        }
        return 0L
    }

    override fun downloadFileText(context: Context, prefs: SharedPreferences, fileItem: CloudFileItem): String? {
        val authHeader = resolveAuthHeader(prefs)
        val downloadUrl = fileItem.customDownloadUrl ?: fileItem.id
        return executeGet(downloadUrl, authHeader)
    }

    override fun downloadByteRange(
        context: Context,
        prefs: SharedPreferences,
        fileItem: CloudFileItem,
        startByte: Long,
        endByte: Long
    ): ByteArray? {
        val authHeader = resolveAuthHeader(prefs)
        val downloadUrl = fileItem.customDownloadUrl ?: fileItem.id
        return executeGetRange(downloadUrl, authHeader, startByte, endByte)
    }

    private fun executeGetRange(urlStr: String, authHeader: String?, startByte: Long, endByte: Long): ByteArray? = attempt("WebDAV GET Range", silent = true) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            if (!authHeader.isNullOrBlank()) {
                setRequestProperty("Authorization", authHeader)
            }
            setRequestProperty("Range", "bytes=$startByte-$endByte")
            connectTimeout = 15000
            readTimeout = 15000
        }
        try {
            if (conn.responseCode == 200 || conn.responseCode == 206) {
                conn.inputStream.use { it.readBytes() }
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun executePropfind(urlStr: String, authHeader: String?): String? = attempt("WebDAV PROPFIND", silent = true) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "PROPFIND"
            setRequestProperty("Depth", "1")
            setRequestProperty("Content-Type", "application/xml; charset=utf-8")
            if (!authHeader.isNullOrBlank()) {
                setRequestProperty("Authorization", authHeader)
            }
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }
        try {
            conn.outputStream.use { it.write(PROPFIND_XML.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } else {
                Log.w(TAG, "[WebDavScanner] PROPFIND returned $code for ${AppUtils.sanitizeUrl(urlStr)}")
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun executeGet(urlStr: String, authHeader: String?): String? = attempt("WebDAV GET", silent = true) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            if (!authHeader.isNullOrBlank()) {
                setRequestProperty("Authorization", authHeader)
            }
            connectTimeout = 15000
            readTimeout = 15000
        }
        try {
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } else {
                Log.w(TAG, "[WebDavScanner] HTTP GET returned ${conn.responseCode} for ${AppUtils.sanitizeUrl(urlStr)}")
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    override fun uploadFileText(
        context: Context,
        prefs: SharedPreferences,
        remoteRelativePath: String,
        content: String
    ): Boolean = attempt("WebDAV PUT text", silent = true) {
        val baseUrl = resolveWebDavUrl(prefs) ?: return@attempt false
        val authHeader = resolveAuthHeader(prefs)
        val normalizedBase = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val cleanPath = remoteRelativePath.trimStart('/')
        val targetUrl = "$normalizedBase$cleanPath"

        val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
            if (!authHeader.isNullOrBlank()) {
                setRequestProperty("Authorization", authHeader)
            }
            setRequestProperty("Content-Type", "application/xml; charset=UTF-8")
        }
        try {
            conn.outputStream.use { os ->
                os.write(content.toByteArray(StandardCharsets.UTF_8))
                os.flush()
            }
            val code = conn.responseCode
            Log.d(TAG, "[WebDavScanner] HTTP PUT to $cleanPath returned $code")
            code in 200..299 || code == 201 || code == 204
        } finally {
            conn.disconnect()
        }
    } ?: false

    override fun uploadFile(
        context: Context,
        prefs: SharedPreferences,
        remoteRelativePath: String,
        file: java.io.File
    ): Boolean = attempt("WebDAV PUT file", silent = true) {
        if (!file.exists() || !file.canRead()) return@attempt false
        uploadFileText(context, prefs, remoteRelativePath, file.readText(StandardCharsets.UTF_8))
    } ?: false
}
