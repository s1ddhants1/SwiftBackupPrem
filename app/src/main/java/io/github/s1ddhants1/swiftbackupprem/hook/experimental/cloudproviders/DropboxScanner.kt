package io.github.s1ddhants1.swiftbackupprem.hook.experimental.cloudproviders

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.Keep
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.hook.experimental.cloudproviders.CloudHttpHelper.findFirstString
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import org.json.JSONObject
import java.nio.charset.StandardCharsets

@Keep
object DropboxScanner : CloudScanner {
    private const val TAG = Consts.TAG
    override val providerName: String = "Dropbox"

    override fun isConfigured(context: Context, prefs: SharedPreferences): Boolean =
        !resolveDropboxToken(prefs).isNullOrBlank()

    private fun resolveDropboxToken(prefs: SharedPreferences): String? =
        prefs.findFirstString("dropbox_access_token", "dropbox_token", "dropbox_auth_token", "dropbox_oauth_token", "dropbox_bearer_token")

    override fun listFiles(context: Context, prefs: SharedPreferences): List<CloudFileItem> {
        val token = resolveDropboxToken(prefs) ?: return emptyList()
        val items = mutableListOf<CloudFileItem>()

        var cursor: String? = null
        var isFirst = true

        do {
            val urlStr = if (isFirst) {
                "https://api.dropboxapi.com/2/files/list_folder"
            } else {
                "https://api.dropboxapi.com/2/files/list_folder/continue"
            }

            val jsonBody = if (isFirst) {
                JSONObject().apply {
                    put("path", "")
                    put("recursive", true)
                    put("include_deleted", false)
                    put("limit", 1000)
                }.toString()
            } else {
                JSONObject().apply {
                    put("cursor", cursor)
                }.toString()
            }

            val respText = CloudHttpHelper.executePost(
                urlStr = urlStr,
                headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"),
                bodyBytes = jsonBody.toByteArray(StandardCharsets.UTF_8)
            ) ?: break

            val root = attempt("parse Dropbox folder entries", silent = true) { JSONObject(respText) } ?: break
            val entries = root.optJSONArray("entries")
            if (entries != null) {
                for (i in 0 until entries.length()) {
                    val entry = entries.getJSONObject(i)
                    val tag = entry.optString(".tag")
                    if (tag == "file") {
                        val name = entry.optString("name")
                        val size = entry.optLong("size", 0L)
                        val pathDisplay = entry.optString("path_display")
                        val timeStr = entry.optString("server_modified").ifBlank { entry.optString("client_modified") }
                        val timestamp = if (timeStr.isNotBlank()) {
                            attempt("parse Dropbox date", silent = true) {
                                java.time.Instant.parse(timeStr).toEpochMilli()
                            } ?: 0L
                        } else 0L

                        if (name.isNotBlank() && pathDisplay.isNotBlank()) {
                            items.add(
                                CloudFileItem(
                                    id = pathDisplay,
                                    name = name,
                                    size = size,
                                    timestamp = timestamp,
                                    provider = providerName,
                                    customDownloadUrl = pathDisplay
                                )
                            )
                        }
                    }
                }
            }

            isFirst = false
            val hasMore = root.optBoolean("has_more", false)
            cursor = if (hasMore) root.optString("cursor").takeIf { it.isNotBlank() } else null
        } while (cursor != null)

        Log.d(TAG, "[DropboxScanner] Discovered ${items.size} backup items in Dropbox")
        return items
    }

    override fun downloadFileText(context: Context, prefs: SharedPreferences, fileItem: CloudFileItem): String? {
        val token = resolveDropboxToken(prefs) ?: return null
        val path = fileItem.customDownloadUrl ?: fileItem.id
        val downloadUrl = "https://content.dropboxapi.com/2/files/download"
        val argObj = JSONObject().apply { put("path", path) }
        return CloudHttpHelper.executePost(
            urlStr = downloadUrl,
            headers = mapOf("Authorization" to "Bearer $token", "Dropbox-API-Arg" to argObj.toString())
        )
    }
}
