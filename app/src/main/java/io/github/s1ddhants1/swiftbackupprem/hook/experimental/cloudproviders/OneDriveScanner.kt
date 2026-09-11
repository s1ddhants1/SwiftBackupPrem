package io.github.s1ddhants1.swiftbackupprem.hook.experimental.cloudproviders

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.Keep
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.AppUtils
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.LinkedList
import java.util.Queue
import java.util.regex.Pattern

@Keep
object OneDriveScanner : CloudScanner {
    private const val TAG = Consts.TAG
    override val providerName: String = "OneDrive"

    override fun isConfigured(context: Context, prefs: SharedPreferences): Boolean {
        val token = resolveToken(prefs)
        return !token.isNullOrBlank()
    }

    fun resolveToken(prefs: SharedPreferences): String? {
        val knownKeys = listOf(
            "onedrive_access_token",
            "onedrive_token",
            "ms_graph_token",
            "onedrive_oauth_token",
            "onedrive_refresh_token",
            "msal_access_token",
            "microsoft_access_token",
            "ms_access_token",
            "graph_token",
            "onedrive_auth_token",
            "onedrive_bearer_token",
            "onedrive_key",
            "onedrive_cloud_token",
            "onedrive_cloud_access_token"
        )
        for (k in knownKeys) {
            val v = prefs.getString(k, null)
            if (!v.isNullOrBlank()) return v.trim()
        }

        try {
            for ((key, value) in prefs.all) {
                if (value is String && value.isNotBlank()) {
                    val lk = key.lowercase()
                    if ((lk.contains("onedrive") || lk.contains("msal") || lk.contains("graph")) &&
                        (lk.contains("token") || lk.contains("access") || lk.contains("bearer") || lk.contains("auth"))
                    ) {
                        return value.trim()
                    }
                }
            }
        } catch (_: Throwable) {}

        return null
    }

    private data class FolderTarget(val url: String, val isRoot: Boolean, val folderPath: String = "")
    private val SWIFT_BACKUP_FOLDER_PATTERN = Pattern.compile("^.*swift[ _-]?backup.*$", Pattern.CASE_INSENSITIVE)

    private fun resolveRelativePath(itemObj: JSONObject, target: FolderTarget, name: String): String {
        val parentRef = itemObj.optJSONObject("parentReference")
        val parentPath = parentRef?.optString("path")
        if (!parentPath.isNullOrBlank()) {
            val rel = if (parentPath.contains("root:")) {
                parentPath.substringAfter("root:").trim('/')
            } else {
                parentPath.trim('/')
            }
            if (rel.isNotBlank()) {
                return "$rel/$name"
            }
        }
        return if (target.folderPath.isBlank()) name else "${target.folderPath}/$name"
    }

    override fun listFiles(context: Context, prefs: SharedPreferences): List<CloudFileItem> {
        val token = resolveToken(prefs) ?: return emptyList()
        val items = mutableListOf<CloudFileItem>()
        val seenFileIds = mutableSetOf<String>()

        val folderQueue: Queue<FolderTarget> = LinkedList()
        val visited = mutableSetOf<String>()

        folderQueue.add(FolderTarget("https://graph.microsoft.com/v1.0/me/drive/root/children", isRoot = true, folderPath = ""))
        folderQueue.add(FolderTarget("https://graph.microsoft.com/v1.0/me/drive/special/approot/children", isRoot = false, folderPath = ""))
        folderQueue.add(FolderTarget("https://graph.microsoft.com/v1.0/me/drive/root:/Swift Backup:/children", isRoot = false, folderPath = "Swift Backup"))
        folderQueue.add(FolderTarget("https://graph.microsoft.com/v1.0/me/drive/root:/Apps/Swift Backup:/children", isRoot = false, folderPath = "Apps/Swift Backup"))
        folderQueue.add(FolderTarget("https://graph.microsoft.com/v1.0/me/drive/root:/SwiftBackup:/children", isRoot = false, folderPath = "SwiftBackup"))

        var scannedCount = 0
        while (folderQueue.isNotEmpty() && scannedCount < 50) {
            val target = folderQueue.poll() ?: break
            if (!visited.add(target.url)) continue
            scannedCount++

            var currentUrl: String? = target.url
            while (currentUrl != null) {
                val urlToFetch = currentUrl
                val respText = executeGet(urlToFetch, token) ?: break
                val root = attempt("parse OneDrive JSON", silent = true) { JSONObject(respText) } ?: break

                val valueArr = root.optJSONArray("value")
                if (valueArr != null) {
                    for (i in 0 until valueArr.length()) {
                        val itemObj = valueArr.getJSONObject(i)
                        val id = itemObj.optString("id")
                        val name = itemObj.optString("name")
                        val size = itemObj.optLong("size", 0L)
                        val timeStr = itemObj.optString("lastModifiedDateTime").ifBlank {
                            itemObj.optJSONObject("fileSystemInfo")?.optString("lastModifiedDateTime") ?: ""
                        }
                        val timestamp = if (timeStr.isNotBlank()) {
                            attempt("parse OneDrive ISO date", silent = true) {
                                java.time.Instant.parse(timeStr).toEpochMilli()
                            } ?: 0L
                        } else 0L
                        val downloadUrl = itemObj.optString("@microsoft.graph.downloadUrl").takeIf { it.isNotBlank() }
                        val isFolder = itemObj.has("folder") || itemObj.optJSONObject("remoteItem")?.has("folder") == true

                        if (isFolder) {
                            val childFolderPath = resolveRelativePath(itemObj, target, name)
                            val childUrl = "https://graph.microsoft.com/v1.0/me/drive/items/$id/children"
                            if (!visited.contains(childUrl)) {
                                if (target.isRoot) {
                                    if (SWIFT_BACKUP_FOLDER_PATTERN.matcher(name).matches()) {
                                        folderQueue.add(FolderTarget(childUrl, isRoot = false, folderPath = childFolderPath))
                                    }
                                } else {
                                    folderQueue.add(FolderTarget(childUrl, isRoot = false, folderPath = childFolderPath))
                                }
                            }
                        } else if (!target.isRoot && name.isNotBlank() && id.isNotBlank()) {
                            if (seenFileIds.add(id)) {
                                val relativePath = resolveRelativePath(itemObj, target, name)
                                items.add(
                                    CloudFileItem(
                                        id = relativePath,
                                        name = name,
                                        size = size,
                                        timestamp = timestamp,
                                        provider = providerName,
                                        customDownloadUrl = downloadUrl
                                    )
                                )
                            }
                        }
                    }
                }

                currentUrl = root.optString("@odata.nextLink").takeIf { it.isNotBlank() }
            }
        }

        // Fallback: If folder crawling returned nothing, search for Swift Backup files across the whole drive
        if (items.isEmpty()) {
            val searchUrl = "https://graph.microsoft.com/v1.0/me/drive/root/search(q='Swift Backup')"
            val searchResp = executeGet(searchUrl, token)
            if (searchResp != null) {
                attempt("parse OneDrive search JSON", silent = true) {
                    val root = JSONObject(searchResp)
                    val valueArr = root.optJSONArray("value")
                    if (valueArr != null) {
                        for (i in 0 until valueArr.length()) {
                            val itemObj = valueArr.getJSONObject(i)
                            val isFolder = itemObj.has("folder") || itemObj.optJSONObject("remoteItem")?.has("folder") == true
                            if (!isFolder) {
                                val id = itemObj.optString("id")
                                val name = itemObj.optString("name")
                                val size = itemObj.optLong("size", 0L)
                                val timeStr = itemObj.optString("lastModifiedDateTime").ifBlank {
                                    itemObj.optJSONObject("fileSystemInfo")?.optString("lastModifiedDateTime") ?: ""
                                }
                                val timestamp = if (timeStr.isNotBlank()) {
                                    attempt("parse OneDrive ISO date", silent = true) {
                                        java.time.Instant.parse(timeStr).toEpochMilli()
                                    } ?: 0L
                                } else 0L
                                val downloadUrl = itemObj.optString("@microsoft.graph.downloadUrl").takeIf { it.isNotBlank() }

                                if (name.isNotBlank() && id.isNotBlank() && seenFileIds.add(id)) {
                                    val relativePath = resolveRelativePath(
                                        itemObj,
                                        FolderTarget("", isRoot = false, folderPath = "Swift Backup"),
                                        name
                                    )
                                    items.add(
                                        CloudFileItem(
                                            id = relativePath,
                                            name = name,
                                            size = size,
                                            timestamp = timestamp,
                                            provider = providerName,
                                            customDownloadUrl = downloadUrl
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Log.d(TAG, "[OneDriveScanner] Discovered ${items.size} backup items in OneDrive")
        return items
    }

    private fun encodeGraphPath(path: String): String {
        return path.split("/").joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
    }

    override fun downloadFileText(context: Context, prefs: SharedPreferences, fileItem: CloudFileItem): String? {
        val token = resolveToken(prefs)
        if (!fileItem.customDownloadUrl.isNullOrBlank()) {
            val direct = executeGet(fileItem.customDownloadUrl, token = null)
            if (!direct.isNullOrBlank()) return direct
        }

        if (token.isNullOrBlank()) return null
        val fallbackUrl = if (fileItem.id.contains("/") || fileItem.id.contains(" ")) {
            "https://graph.microsoft.com/v1.0/me/drive/root:/" + encodeGraphPath(fileItem.id) + ":/content"
        } else {
            "https://graph.microsoft.com/v1.0/me/drive/items/${fileItem.id}/content"
        }
        return executeGet(fallbackUrl, token)
    }

    override fun downloadByteRange(
        context: Context,
        prefs: SharedPreferences,
        fileItem: CloudFileItem,
        startByte: Long,
        endByte: Long
    ): ByteArray? {
        val token = resolveToken(prefs)
        if (!fileItem.customDownloadUrl.isNullOrBlank()) {
            val direct = executeGetRange(fileItem.customDownloadUrl, token = null, startByte, endByte)
            if (direct != null && direct.isNotEmpty()) return direct
        }

        if (token.isNullOrBlank()) return null
        val fallbackUrl = if (fileItem.id.contains("/") || fileItem.id.contains(" ")) {
            "https://graph.microsoft.com/v1.0/me/drive/root:/" + encodeGraphPath(fileItem.id) + ":/content"
        } else {
            "https://graph.microsoft.com/v1.0/me/drive/items/${fileItem.id}/content"
        }
        return executeGetRange(fallbackUrl, token, startByte, endByte)
    }

    private fun executeGetRange(
        urlStr: String,
        token: String?,
        startByte: Long,
        endByte: Long
    ): ByteArray? = CloudHttpHelper.executeGetRange(
        urlStr = urlStr,
        headers = token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap(),
        startByte = startByte,
        endByte = endByte,
        onAuthRedirect = { loc ->
            if (loc.contains("blob.core.windows.net") || loc.contains("1drv.ms") || !loc.contains("graph.microsoft.com")) {
                emptyMap()
            } else {
                token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
            }
        }
    )

    private fun executeGet(
        urlStr: String,
        token: String?
    ): String? = CloudHttpHelper.executeGet(
        urlStr = urlStr,
        headers = token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap(),
        onAuthRedirect = { loc ->
            if (loc.contains("blob.core.windows.net") || loc.contains("1drv.ms") || !loc.contains("graph.microsoft.com")) {
                emptyMap()
            } else {
                token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
            }
        }
    )
}
