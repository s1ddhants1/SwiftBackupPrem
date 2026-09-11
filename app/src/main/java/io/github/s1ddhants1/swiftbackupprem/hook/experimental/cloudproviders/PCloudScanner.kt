package io.github.s1ddhants1.swiftbackupprem.hook.experimental.cloudproviders

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.Keep
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.hook.experimental.cloudproviders.CloudHttpHelper.findFirstString
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import org.json.JSONObject

@Keep
object PCloudScanner : CloudScanner {
    private const val TAG = Consts.TAG
    override val providerName: String = "pCloud"

    override fun isConfigured(context: Context, prefs: SharedPreferences): Boolean =
        !resolveToken(prefs).isNullOrBlank()

    private fun resolveToken(prefs: SharedPreferences): String? =
        prefs.findFirstString("pcloud_access_token", "pcloud_token", "pcloud_auth_token", "pcloud_auth")

    override fun listFiles(context: Context, prefs: SharedPreferences): List<CloudFileItem> {
        val token = resolveToken(prefs) ?: return emptyList()
        val items = mutableListOf<CloudFileItem>()

        val urlStr = "https://api.pcloud.com/listfolder?folderid=0&recursive=1"
        val respText = CloudHttpHelper.executeGet(urlStr, mapOf("Authorization" to "Bearer $token")) ?: return emptyList()
        val root = attempt("parse pCloud folder items", silent = true) { JSONObject(respText) } ?: return emptyList()

        val metadata = root.optJSONObject("metadata") ?: root
        traverseMetadata(metadata, items)

        Log.d(TAG, "[PCloudScanner] Discovered ${items.size} backup items in pCloud")
        return items
    }

    private fun traverseMetadata(folderObj: JSONObject, items: MutableList<CloudFileItem>) {
        val contents = folderObj.optJSONArray("contents") ?: return
        for (i in 0 until contents.length()) {
            val item = contents.getJSONObject(i)
            val isFolder = item.optBoolean("isfolder", false)
            if (isFolder) {
                traverseMetadata(item, items)
            } else {
                val name = item.optString("name")
                val size = item.optLong("size", 0L)
                val fileId = item.optString("fileid")
                if (name.isNotBlank() && fileId.isNotBlank()) {
                    items.add(CloudFileItem(id = fileId, name = name, size = size, provider = providerName))
                }
            }
        }
    }

    override fun downloadFileText(context: Context, prefs: SharedPreferences, fileItem: CloudFileItem): String? {
        val token = resolveToken(prefs) ?: return null
        val linkUrl = "https://api.pcloud.com/getfilelink?fileid=${fileItem.id}"
        val respText = CloudHttpHelper.executeGet(linkUrl, mapOf("Authorization" to "Bearer $token")) ?: return null
        val linkRoot = attempt("parse pCloud link", silent = true) { JSONObject(respText) } ?: return null

        val hosts = linkRoot.optJSONArray("hosts")
        val path = linkRoot.optString("path")
        if (hosts != null && hosts.length() > 0 && path.isNotBlank()) {
            val host = hosts.getString(0)
            return CloudHttpHelper.executeGet("https://$host$path")
        }
        return null
    }
}
