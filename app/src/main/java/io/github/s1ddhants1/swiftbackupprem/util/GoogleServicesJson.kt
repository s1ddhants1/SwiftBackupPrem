package io.github.s1ddhants1.swiftbackupprem.util

import io.github.s1ddhants1.swiftbackupprem.Consts
import org.json.JSONArray
import org.json.JSONObject

object GoogleServicesJson {

    fun applyToPrefs(json: JSONObject, prefs: PreferencesManager) {
        json.optJSONArray("client")?.optJSONObject(0)?.let { client ->
            client.optJSONObject("client_info")?.optString("mobilesdk_app_id")?.takeIf { it.isNotBlank() }?.let { prefs.googleAppId = it }
            client.optJSONArray("api_key")?.optJSONObject(0)?.optString("current_key")?.takeIf { it.isNotBlank() }?.let { prefs.googleApiKey = it }
            client.optJSONArray("oauth_client")?.let { oauthArray ->
                for (i in 0 until oauthArray.length()) {
                    val oc = oauthArray.optJSONObject(i) ?: continue
                    val type = oc.optInt("client_type", -1)
                    val pkg = oc.optJSONObject("android_info")?.optString("package_name")
                    val cId = oc.optString("client_id")
                    if (cId.isNotBlank() && (type == 1 || pkg == Consts.packageName)) {
                        prefs.clientId = cId
                        break
                    }
                }
            }
        }
        json.optJSONObject("project_info")?.let { proj ->
            proj.optString("firebase_url").takeIf { it.isNotBlank() }?.let { prefs.firebaseDatabaseUrl = it }
            proj.optString("project_number").takeIf { it.isNotBlank() }?.let { prefs.gcmDefaultSenderId = it }
            proj.optString("storage_bucket").takeIf { it.isNotBlank() }?.let { prefs.googleStorageBucket = it }
            proj.optString(Consts.projectId).takeIf { it.isNotBlank() }?.let { prefs.projectId = it }
        }
        json.optString(Consts.oauthClientId).takeIf { it.isNotBlank() }?.let { prefs.clientId = it }
    }

    fun buildFromPrefs(prefs: PreferencesManager): JSONObject = JSONObject().apply {
        put("client", JSONArray().apply {
            put(JSONObject().apply {
                put("client_info", JSONObject().apply { put("mobilesdk_app_id", prefs.googleAppId) })
                put("api_key", JSONArray().apply { put(JSONObject().apply { put("current_key", prefs.googleApiKey) }) })
            })
        })
        put("project_info", JSONObject().apply {
            put("firebase_url", prefs.firebaseDatabaseUrl)
            put("project_number", prefs.gcmDefaultSenderId)
            put("storage_bucket", prefs.googleStorageBucket)
            put(Consts.projectId, prefs.projectId)
        })
        put(Consts.oauthClientId, prefs.clientId)
    }
}
