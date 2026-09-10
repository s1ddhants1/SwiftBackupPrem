package io.github.s1ddhants1.swiftbackupprem.hook.experimental.cloudproviders

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.Keep
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.AppUtils
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Keep
object GoogleDriveScanner : CloudScanner {
    private const val TAG = Consts.TAG
    override val providerName: String = "GoogleDrive"

    override fun isConfigured(context: Context, prefs: SharedPreferences): Boolean {
        val token = prefs.getString("nogms_access_token", null)
            ?: prefs.getString("nogms_auth_state", null)
        val folderId = prefs.getString("google_drive_cloud_main_folder_id", null)
        return !token.isNullOrBlank() && !folderId.isNullOrBlank()
    }

    override fun listFiles(context: Context, prefs: SharedPreferences): List<CloudFileItem> {
        val token = getOrRefreshToken(context, prefs) ?: return emptyList()
        val folderId = prefs.getString("google_drive_cloud_main_folder_id", null) ?: return emptyList()

        val items = mutableListOf<CloudFileItem>()
        var pageToken: String? = null

        do {
            val q = URLEncoder.encode("'$folderId' in parents and trashed=false", "UTF-8")
            val pageParam = if (pageToken != null) "&pageToken=$pageToken" else ""
            val urlStr = "https://www.googleapis.com/drive/v3/files?q=$q&fields=nextPageToken,files(id,name,size,modifiedTime,createdTime,thumbnailLink)&pageSize=1000$pageParam"

            val respText = executeGet(context, prefs, urlStr, token) ?: break
            val root = attempt("parse drive files", silent = true) { JSONObject(respText) } ?: break
            val filesArr = root.optJSONArray("files") ?: JSONArray()

            for (i in 0 until filesArr.length()) {
                val fileObj = filesArr.getJSONObject(i)
                val id = fileObj.optString("id")
                val name = fileObj.optString("name")
                val size = fileObj.optLong("size", 0L)
                val thumbnailLink = fileObj.optString("thumbnailLink").takeIf { it.isNotBlank() }
                val timeStr = fileObj.optString("modifiedTime").ifBlank { fileObj.optString("createdTime") }
                val timestamp = if (timeStr.isNotBlank()) {
                    try {
                        java.time.Instant.parse(timeStr).toEpochMilli()
                    } catch (_: Throwable) {
                        0L
                    }
                } else 0L

                if (id.isNotBlank() && name.isNotBlank()) {
                    items.add(
                        CloudFileItem(
                            id = id,
                            name = name,
                            size = size,
                            timestamp = timestamp,
                            thumbnailLink = thumbnailLink,
                            provider = providerName
                        )
                    )
                }
            }

            pageToken = root.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null)

        Log.d(TAG, "[GoogleDriveScanner] Found ${items.size} files in folder $folderId")
        return items
    }

    override fun downloadFileText(context: Context, prefs: SharedPreferences, fileItem: CloudFileItem): String? {
        val token = getOrRefreshToken(context, prefs) ?: return null
        val urlStr = "https://www.googleapis.com/drive/v3/files/${fileItem.id}?alt=media"
        return executeGet(context, prefs, urlStr, token)
    }

    override fun downloadByteRange(
        context: Context,
        prefs: SharedPreferences,
        fileItem: CloudFileItem,
        startByte: Long,
        endByte: Long
    ): ByteArray? {
        val token = getOrRefreshToken(context, prefs) ?: return null
        val urlStr = "https://www.googleapis.com/drive/v3/files/${fileItem.id}?alt=media"
        return executeGetRange(context, prefs, urlStr, token, startByte, endByte)
    }

    private fun executeGetRange(
        context: Context,
        prefs: SharedPreferences,
        urlStr: String,
        token: String,
        startByte: Long,
        endByte: Long
    ): ByteArray? = attempt("Drive HTTP GET Range", silent = true) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Range", "bytes=$startByte-$endByte")
            connectTimeout = 15000
            readTimeout = 15000
        }
        try {
            if (conn.responseCode == 200 || conn.responseCode == 206) {
                conn.inputStream.use { it.readBytes() }
            } else if (conn.responseCode == 401) {
                Log.w(TAG, "[GoogleDriveScanner] 401 on Range GET, attempting token refresh...")
                val freshToken = getOrRefreshToken(context, prefs, forceRefresh = true)
                if (!freshToken.isNullOrBlank() && freshToken != token) {
                    val retryConn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Authorization", "Bearer $freshToken")
                        setRequestProperty("Range", "bytes=$startByte-$endByte")
                        connectTimeout = 15000
                        readTimeout = 15000
                    }
                    try {
                        if (retryConn.responseCode == 200 || retryConn.responseCode == 206) {
                            return@attempt retryConn.inputStream.use { it.readBytes() }
                        }
                    } finally {
                        retryConn.disconnect()
                    }
                }
                null
            } else {
                Log.w(TAG, "[GoogleDriveScanner] HTTP Range GET returned ${conn.responseCode} for ${AppUtils.sanitizeUrl(urlStr)} (bytes=$startByte-$endByte)")
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun executeGet(
        context: Context,
        prefs: SharedPreferences,
        urlStr: String,
        token: String
    ): String? = attempt("Drive HTTP GET", silent = true) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 15000
            readTimeout = 15000
        }
        try {
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } else if (conn.responseCode == 401) {
                Log.w(TAG, "[GoogleDriveScanner] 401 on GET, attempting token refresh...")
                val freshToken = getOrRefreshToken(context, prefs, forceRefresh = true)
                if (!freshToken.isNullOrBlank() && freshToken != token) {
                    val retryConn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Authorization", "Bearer $freshToken")
                        connectTimeout = 15000
                        readTimeout = 15000
                    }
                    try {
                        if (retryConn.responseCode == 200) {
                            return@attempt retryConn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                        }
                    } finally {
                        retryConn.disconnect()
                    }
                }
                null
            } else {
                Log.w(TAG, "[GoogleDriveScanner] HTTP GET returned ${conn.responseCode} for ${AppUtils.sanitizeUrl(urlStr)}")
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
    ): Boolean = attempt("Drive HTTP Upload Text", silent = true) {
        val token = getOrRefreshToken(context, prefs) ?: return@attempt false
        val folderId = prefs.getString("google_drive_cloud_main_folder_id", null) ?: return@attempt false
        val fileName = remoteRelativePath.substringAfterLast('/')

        fun sendMultipart(tok: String): Int {
            val boundary = "==SBP_GDRIVE_BOUNDARY_${System.currentTimeMillis()}=="
            val urlStr = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $tok")
                setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
                connectTimeout = 20000
                readTimeout = 20000
            }

            val metadataJson = JSONObject().apply {
                put("name", fileName)
                put("parents", JSONArray().put(folderId))
                put("mimeType", "application/json")
            }.toString()

            val body = buildString {
                append("--$boundary\r\n")
                append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                append(metadataJson)
                append("\r\n--$boundary\r\n")
                append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                append(content)
                append("\r\n--$boundary--\r\n")
            }

            return try {
                conn.outputStream.use { os ->
                    os.write(body.toByteArray(StandardCharsets.UTF_8))
                    os.flush()
                }
                val code = conn.responseCode
                Log.d(TAG, "[GoogleDriveScanner] Upload $fileName returned $code")
                code
            } finally {
                conn.disconnect()
            }
        }

        var code = sendMultipart(token)
        if (code == 401) {
            val freshToken = getOrRefreshToken(context, prefs, forceRefresh = true)
            if (!freshToken.isNullOrBlank() && freshToken != token) {
                code = sendMultipart(freshToken)
            }
        }

        code in 200..299
    } ?: false

    private fun getOrRefreshToken(
        context: Context,
        prefs: SharedPreferences,
        forceRefresh: Boolean = false
    ): String? {
        val currentToken = prefs.getString("nogms_access_token", null)
        if (!forceRefresh && !currentToken.isNullOrBlank()) {
            return currentToken
        }

        val authStateRaw = prefs.getString("nogms_auth_state", null) ?: return currentToken
        return attempt("refresh Google Drive access token", silent = true) {
            val json = JSONObject(authStateRaw)
            val refreshToken = json.optString("refreshToken").takeIf { it.isNotBlank() }
                ?: json.optJSONObject("mLastTokenResponse")?.optString("refresh_token")?.takeIf { it.isNotBlank() }
                ?: return@attempt currentToken

            val lastAuthReq = json.optJSONObject("lastAuthorizationResponse")?.optJSONObject("request")
            val clientId = lastAuthReq?.optString("clientId")
                ?: json.optJSONObject("mLastTokenResponse")?.optJSONObject("request")?.optString("clientId")
                ?: "65312358122-rain54ntj087k3lqousgb4l94isnvrqb.apps.googleusercontent.com"

            val url = URL("https://oauth2.googleapis.com/token")
            val postData = "grant_type=refresh_token&client_id=${URLEncoder.encode(clientId, "UTF-8")}&refresh_token=${URLEncoder.encode(refreshToken, "UTF-8")}"
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connectTimeout = 15000
                readTimeout = 15000
            }
            try {
                conn.outputStream.use { it.write(postData.toByteArray(StandardCharsets.UTF_8)) }
                if (conn.responseCode in 200..299) {
                    val respText = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    val respJson = JSONObject(respText)
                    val newAccessToken = respJson.optString("access_token")
                    if (newAccessToken.isNotBlank()) {
                        Log.i(TAG, "[GoogleDriveScanner] Successfully refreshed Google Drive access token")
                        try {
                            val sp = context.getSharedPreferences("org.swiftapps.swiftbackup_preferences", Context.MODE_PRIVATE)
                            sp.edit().putString("nogms_access_token", newAccessToken).apply()
                        } catch (_: Throwable) {}
                        return@attempt newAccessToken
                    }
                } else {
                    Log.w(TAG, "[GoogleDriveScanner] Token refresh returned HTTP ${conn.responseCode}")
                }
            } finally {
                conn.disconnect()
            }
            currentToken
        } ?: currentToken
    }
}
