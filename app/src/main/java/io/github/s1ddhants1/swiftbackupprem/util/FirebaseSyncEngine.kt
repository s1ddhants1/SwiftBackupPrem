package io.github.s1ddhants1.swiftbackupprem.util

import android.content.Context
import android.os.Environment
import android.util.Log
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.hook.experimental.cloudproviders.CloudScannerRegistry
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Engine to sync reconstructed and cloud discovered backup metadata directly
 * to a Custom Firebase Realtime Database instance.
 */
object FirebaseSyncEngine {

    private const val TAG = Consts.TAG

    data class SyncResult(
        val totalSynced: Int,
        val totalAlreadyExisting: Int = 0,
        val totalFailed: Int = 0,
        val errors: List<String> = emptyList()
    )

    data class AuthCredentials(
        val uid: String,
        val idToken: String?,
        val email: String? = null
    )

    fun cleanDbUrl(rawUrl: String): String {
        var url = rawUrl.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        return url
    }

    /**
     * Resolves authenticated Firebase credentials (UID and Firebase ID Token JWT)
     * by inspecting Swift Backup's OAuth state and exchanging tokens via Firebase Auth REST API.
     */
    fun resolveAuthCredentials(context: Context?, prefs: PreferencesManager): AuthCredentials? {
        val apiKey = prefs.googleApiKey.trim().takeIf { it.isNotBlank() }

        // 1. Try reading Swift Backup preferences
        val prefsContent = readSwiftBackupPreferences(context)
        Log.d(TAG, "[FirebaseSync] readSwiftBackupPreferences content length: ${prefsContent?.length ?: 0}, apiKey present: ${apiKey != null}, clientId: ${prefs.clientId.isNotBlank()}")
        if (!prefsContent.isNullOrBlank()) {
            val creds = parseCredentialsFromPrefsContent(prefsContent, apiKey, prefs.clientId.trim())
            if (creds != null) return creds
        }

        // 2. Fallback: resolve candidate UID without token if rules allow unauthenticated writes
        val fallbackUids = BackupCrypto.resolveCandidateUids(context, ClassLoader.getSystemClassLoader())
        val selectedUid = fallbackUids.firstOrNull { it != BackupMigratorEngine.SWIFT_BACKUP_ANONYMOUS_UID }
            ?: fallbackUids.firstOrNull()

        return if (!selectedUid.isNullOrBlank()) {
            Log.d(TAG, "[FirebaseSync] Using fallback unauthenticated UID: $selectedUid")
            AuthCredentials(uid = selectedUid, idToken = null)
        } else {
            null
        }
    }

    private fun readSwiftBackupPreferences(context: Context?): String? {
        val candidateFiles = listOf(
            File("/storage/emulated/0/SwiftBackup/.sbp_auth_state"),
            File("/sdcard/SwiftBackup/.sbp_auth_state"),
            File(context?.getExternalFilesDir(null), ".sbp_auth_state"),
            File("/storage/emulated/0/Android/data/${Consts.packageName}/files/.sbp_auth_state"),
            File("/storage/emulated/0/Android/data/io.github.s1ddhants1.swiftbackupprem/files/.sbp_auth_state"),
            File("/data/data/org.swiftapps.swiftbackup/shared_prefs/org.swiftapps.swiftbackup_preferences.xml"),
            File("/data/user/0/org.swiftapps.swiftbackup/shared_prefs/org.swiftapps.swiftbackup_preferences.xml")
        )

        for (f in candidateFiles) {
            if (f.exists() && f.canRead()) {
                try {
                    val txt = f.readText(StandardCharsets.UTF_8).trim()
                    if (txt.isNotBlank()) return txt
                } catch (_: Throwable) {}
            }
        }

        // Try reading via root shell if direct read fails
        val suBins = listOf("su", "/system/bin/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/data/adb/magisk/su")
        for (su in suBins) {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf(su, "-c", "cat /data/data/org.swiftapps.swiftbackup/shared_prefs/org.swiftapps.swiftbackup_preferences.xml 2>/dev/null"))
                val reader = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
                val out = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    out.appendLine(line)
                }
                proc.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                val res = out.toString().trim()
                if (res.isNotBlank()) return res
            } catch (_: Throwable) {}
        }

        return null
    }

    private fun parseCredentialsFromPrefsContent(rawText: String, apiKey: String?, prefClientId: String): AuthCredentials? {
        var refreshToken: String? = null
        var clientId: String? = prefClientId.takeIf { it.isNotBlank() }
        var rawGoogleIdToken: String? = null
        var userEmail: String? = null

        val trimmed = rawText.trim()
        val jsonObj: JSONObject? = if (trimmed.startsWith("{")) {
            try { JSONObject(trimmed) } catch (_: Throwable) { null }
        } else {
            val matcherNoGms = Pattern.compile("name=\"nogms_auth_state\">([^<]+)<").matcher(rawText)
            if (matcherNoGms.find()) {
                val rawJsonStr = matcherNoGms.group(1)?.replace("&quot;", "\"")?.replace("&amp;", "&")?.replace("\\/", "/")
                if (!rawJsonStr.isNullOrBlank()) {
                    try { JSONObject(rawJsonStr) } catch (_: Throwable) { null }
                } else null
            } else null
        }

        if (jsonObj != null) {
            val lastAuthReq = jsonObj.optJSONObject("lastAuthorizationResponse")?.optJSONObject("request")
            val lastTokenReq = jsonObj.optJSONObject("mLastTokenResponse")?.optJSONObject("request")

            refreshToken = jsonObj.optString("refreshToken").takeIf { it.isNotBlank() }
                ?: jsonObj.optString("refresh_token").takeIf { it.isNotBlank() }
                ?: jsonObj.optJSONObject("mLastTokenResponse")?.optString("refresh_token")?.takeIf { it.isNotBlank() }

            if (clientId.isNullOrBlank()) {
                clientId = lastAuthReq?.optString("clientId")?.takeIf { it.isNotBlank() }
                    ?: lastTokenReq?.optString("clientId")?.takeIf { it.isNotBlank() }
            }
            rawGoogleIdToken = jsonObj.optJSONObject("mLastTokenResponse")?.optString("id_token")?.takeIf { it.isNotBlank() }

            // Try extracting email from id_token claims if present
            rawGoogleIdToken?.let { tok ->
                try {
                    val parts = tok.split(".")
                    if (parts.size >= 2) {
                        val payloadJson = JSONObject(String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP), StandardCharsets.UTF_8))
                        userEmail = payloadJson.optString("email").takeIf { it.isNotBlank() }
                    }
                } catch (_: Throwable) {}
            }

            Log.d(TAG, "[FirebaseSync] Parsed auth state: refreshToken present: ${!refreshToken.isNullOrBlank()}, clientId: $clientId, rawGoogleIdToken: ${!rawGoogleIdToken.isNullOrBlank()}, email: $userEmail")
        }

        // If refreshToken and apiKey and clientId are available, exchange for fresh Firebase Auth token
        if (!refreshToken.isNullOrBlank() && !clientId.isNullOrBlank() && !apiKey.isNullOrBlank()) {
            val freshTokens = exchangeGoogleRefreshTokenForFirebaseToken(refreshToken, clientId, apiKey, userEmail)
            if (freshTokens != null) return freshTokens
        }

        // If we have a Google ID token and apiKey, try signInWithIdp
        if (!rawGoogleIdToken.isNullOrBlank() && !apiKey.isNullOrBlank()) {
            val fbTokens = exchangeGoogleIdTokenForFirebaseToken(rawGoogleIdToken, apiKey, userEmail)
            if (fbTokens != null) return fbTokens
        }

        return null
    }

    private fun exchangeGoogleRefreshTokenForFirebaseToken(
        refreshToken: String,
        clientId: String,
        apiKey: String,
        fallbackEmail: String? = null
    ): AuthCredentials? = attempt("exchange refresh token for Firebase ID token", silent = false) {
        // 1. Refresh Google OAuth Token
        val oauthEndpoint = "https://oauth2.googleapis.com/token"
        val postBody = "client_id=" + java.net.URLEncoder.encode(clientId, "UTF-8") +
                "&refresh_token=" + java.net.URLEncoder.encode(refreshToken, "UTF-8") +
                "&grant_type=refresh_token"

        val conn = (URL(oauthEndpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }

        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(postBody); it.flush() }
        if (conn.responseCode !in 200..299) {
            val err = attempt("read err stream", silent = true) { conn.errorStream?.bufferedReader()?.use { it.readText() } }
            Log.w(TAG, "[FirebaseSync] Failed to refresh Google OAuth token (HTTP ${conn.responseCode}): $err")
            conn.disconnect()
            return@attempt null
        }

        val googleResp = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { it.readText() }
        conn.disconnect()
        val googleIdToken = JSONObject(googleResp).optString("id_token")
        if (googleIdToken.isBlank()) {
            Log.w(TAG, "[FirebaseSync] No id_token returned from Google OAuth refresh: $googleResp")
            return@attempt null
        }

        // 2. Exchange with Firebase Identity Toolkit
        exchangeGoogleIdTokenForFirebaseToken(googleIdToken, apiKey, fallbackEmail)
    }

    private fun exchangeGoogleIdTokenForFirebaseToken(
        googleIdToken: String,
        apiKey: String,
        fallbackEmail: String? = null
    ): AuthCredentials? = attempt("exchange Google ID token for Firebase Auth ID token", silent = false) {
        val endpoint = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=$apiKey"
        val payload = JSONObject().apply {
            put("postBody", "id_token=$googleIdToken&providerId=google.com")
            put("requestUri", "http://localhost")
            put("returnSecureToken", true)
        }

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }

        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(payload.toString()); it.flush() }
        if (conn.responseCode !in 200..299) {
            val err = attempt("read err stream", silent = true) { conn.errorStream?.bufferedReader()?.use { it.readText() } }
            Log.w(TAG, "[FirebaseSync] Failed to exchange token with Firebase Auth (HTTP ${conn.responseCode}): $err")
            conn.disconnect()
            return@attempt null
        }

        val fbResp = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { it.readText() }
        conn.disconnect()
        val json = JSONObject(fbResp)
        val idToken = json.optString("idToken")
        val uid = json.optString("localId")
        val email = json.optString("email").takeIf { it.isNotBlank() } ?: fallbackEmail

        if (uid.isNotBlank() && idToken.isNotBlank()) {
            Log.i(TAG, "[FirebaseSync] Successfully resolved Firebase Auth UID: $uid (email: $email)")
            AuthCredentials(uid = uid, idToken = idToken, email = email)
        } else {
            null
        }
    }

    /**
     * Fetches existing cloud_v1 tree from /users/<uid>/cloud_v1.json.
     */
    fun fetchCloudV1Tree(firebaseDbUrl: String, uid: String, idToken: String? = null): JSONObject? {
        return attempt("fetch cloud_v1 tree from RTDB", silent = true) {
            val base = cleanDbUrl(firebaseDbUrl)
            val authParam = if (!idToken.isNullOrBlank()) "?auth=$idToken" else ""
            val endpoint = "$base/users/$uid/cloud_v1.json$authParam"

            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "application/json")
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val respText = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { it.readText() }
                conn.disconnect()
                if (respText.isNotBlank() && respText != "null") {
                    JSONObject(respText)
                } else {
                    JSONObject()
                }
            } else {
                conn.disconnect()
                null
            }
        }
    }

    /**
     * Deletes the legacy /users/<uid>/backups node if present to ensure single canonical tree.
     */
    fun deleteLegacyBackupsNode(firebaseDbUrl: String, uid: String, idToken: String? = null): Boolean {
        return attempt("delete legacy backups node from RTDB", silent = true) {
            val base = cleanDbUrl(firebaseDbUrl)
            val authParam = if (!idToken.isNullOrBlank()) "?auth=$idToken" else ""
            val endpoint = "$base/users/$uid/backups.json$authParam"

            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = 10000
                readTimeout = 10000
            }

            val responseCode = conn.responseCode
            conn.disconnect()
            if (responseCode in 200..299) {
                Log.i(TAG, "[FirebaseSync] Cleaned up legacy /users/$uid/backups node from RTDB")
                true
            } else {
                false
            }
        } ?: false
    }

    /**
     * Pushes a single app's backup metadata to /users/<uid>/cloud_v1/<cloudDir>/tags/<tag>/apps/<sanitizedAppId>/<backupId>.json
     */
    private fun executePut(endpoint: String, payload: String): Boolean {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }
        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
            writer.write(payload)
            writer.flush()
        }
        val code = conn.responseCode
        conn.disconnect()
        return code in 200..299
    }

    fun syncAppMetadata(
        firebaseDbUrl: String,
        uid: String,
        cloudDir: String = "local",
        tag: String = android.os.Build.MODEL,
        pkgName: String,
        backupId: String,
        metadataJson: JSONObject,
        idToken: String? = null
    ): Boolean = attempt("sync $pkgName ($backupId) metadata to Firebase Realtime Database", silent = true) {
        val base = cleanDbUrl(firebaseDbUrl)
        val sanitizedAppId = pkgName.replace(".", "")
        val resolvedTag = if (tag.isNotBlank()) tag else metadataJson.optString("backupTag", android.os.Build.MODEL)
        val authParam = if (!idToken.isNullOrBlank()) "?auth=$idToken" else ""
        val endpoint = "$base/users/$uid/cloud_v1/$cloudDir/tags/$resolvedTag/apps/$sanitizedAppId/$backupId.json$authParam"

        val ok = executePut(endpoint, metadataJson.toString())
        if (ok) {
            Log.d(TAG, "[FirebaseSync] Successfully synced metadata for $pkgName ($backupId) to $endpoint")
        } else {
            Log.w(TAG, "[FirebaseSync] Failed to sync $pkgName ($backupId)")
        }
        ok
    } ?: false

    /**
     * Pushes a single folder's backup metadata to /users/<uid>/cloud_v1/<cloudDir>/tags/<tag>/folders/<folderId>.json
     */
    fun syncFolderMetadata(
        firebaseDbUrl: String,
        uid: String,
        cloudDir: String = "local",
        tag: String = android.os.Build.MODEL,
        folderId: String,
        metadataJson: JSONObject,
        idToken: String? = null
    ): Boolean = attempt("sync folder $folderId metadata to Firebase Realtime Database", silent = true) {
        val base = cleanDbUrl(firebaseDbUrl)
        val resolvedTag = if (tag.isNotBlank()) tag else metadataJson.optString("backupTag", android.os.Build.MODEL)
        val authParam = if (!idToken.isNullOrBlank()) "?auth=$idToken" else ""
        val endpoint = "$base/users/$uid/cloud_v1/$cloudDir/tags/$resolvedTag/folders/$folderId.json$authParam"

        val ok = executePut(endpoint, metadataJson.toString())
        if (ok) {
            Log.d(TAG, "[FirebaseSync] Successfully synced folder metadata for $folderId to $endpoint")
        } else {
            Log.w(TAG, "[FirebaseSync] Failed to sync folder $folderId")
        }
        ok
    } ?: false

    /**
     * Resolves the cloud_v1 directory name for a given provider and user email.
     */
    fun resolveCloudDir(provider: String, email: String?, existingCloudV1: JSONObject?): String {
        val normalizedProvider = when (provider.lowercase().replace(" ", "").replace("_", "")) {
            "googledrive" -> "google_drive"
            "onedrive" -> "one_drive"
            "dropbox" -> "dropbox"
            "box" -> "box"
            "pcloud" -> "pcloud"
            "webdav" -> "webdav"
            "s3" -> "s3"
            "mega" -> "mega"
            "nextcloud" -> "nextcloud"
            "owncloud" -> "owncloud"
            else -> provider.lowercase().replace(" ", "_")
        }

        // 1. Try matching against an existing cloud_v1 key
        if (existingCloudV1 != null) {
            for (key in existingCloudV1.keys()) {
                val keyNorm = key.lowercase()
                if (keyNorm.startsWith("${normalizedProvider} ") || keyNorm.startsWith("${normalizedProvider}(") || keyNorm == normalizedProvider) {
                    return key
                }
            }
        }

        // 2. Build standard key "<provider> (<sanitizedEmail>)"
        if (!email.isNullOrBlank()) {
            val sanitizedEmail = email.replace(Regex("[/.#$\\[\\]]"), "")
            return "$normalizedProvider ($sanitizedEmail)"
        }

        return normalizedProvider
    }

    /**
     * Reads cloud discovery cache JSON from disk.
     */
    fun readCloudDiscoveryCache(context: Context?): JSONObject? {
        val canonicalFile = File(Environment.getExternalStorageDirectory(), "SwiftBackup/cloud_discovered_cache.json")
        if (canonicalFile.exists() && canonicalFile.canRead()) {
            try {
                val txt = canonicalFile.readText(StandardCharsets.UTF_8)
                if (txt.isNotBlank() && txt.startsWith("{")) {
                    Log.i(TAG, "[FirebaseSync] Loaded cloud discovery cache from: ${canonicalFile.absolutePath} (${txt.length} bytes)")
                    return JSONObject(txt)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "[FirebaseSync] Failed reading cache file ${canonicalFile.absolutePath}: ${t.message}")
            }
        }

        // Secondary fallback in case storage path alias differs
        val fallbackFile = File("/sdcard/SwiftBackup/cloud_discovered_cache.json")
        if (fallbackFile.exists() && fallbackFile.canRead()) {
            try {
                val txt = fallbackFile.readText(StandardCharsets.UTF_8)
                if (txt.isNotBlank() && txt.startsWith("{")) {
                    return JSONObject(txt)
                }
            } catch (_: Throwable) {}
        }

        Log.w(TAG, "[FirebaseSync] Cloud discovery cache not found at /sdcard/SwiftBackup/cloud_discovered_cache.json")
        return null
    }

    /**
     * Performs a full sync of Cloud Discovered Cache to Swift Backup's native cloud_v1 hierarchy in RTDB.
     */
    fun syncAll(context: Context, prefs: PreferencesManager): SyncResult {
        val errors = mutableListOf<String>()
        var totalSynced = 0
        var totalAlreadyExisting = 0
        var totalFailed = 0

        if (!prefs.customFirebaseApp || !prefs.syncMetadataToFirebase) {
            return SyncResult(0, 0, 0, listOf("Firebase metadata sync is disabled."))
        }

        val dbUrl = prefs.firebaseDatabaseUrl.trim()
        if (dbUrl.isBlank()) {
            return SyncResult(0, 0, 0, listOf("Firebase Realtime Database URL is empty."))
        }

        val creds = resolveAuthCredentials(context, prefs)
        if (creds == null) {
            return SyncResult(0, 0, 0, listOf("Could not resolve Firebase Auth credentials. Please ensure Swift Backup is logged into your Custom Firebase account."))
        }

        val uid = creds.uid
        val idToken = creds.idToken
        val userEmail = creds.email
        Log.i(TAG, "[FirebaseSync] Starting sync for Firebase UID: $uid (Token attached: ${idToken != null}, email: $userEmail)")

        // 1. Fetch existing cloud_v1 tree to detect already existing records
        val existingCloudV1 = fetchCloudV1Tree(dbUrl, uid, idToken)

        // 2. Sync Cloud Discovered Backups into cloud_v1
        val cacheJson = readCloudDiscoveryCache(context)
        if (cacheJson != null) {
            val appsObj = cacheJson.optJSONObject("apps")
            if (appsObj != null) {
                appsObj.keys().forEach { pkg ->
                    val sanitizedAppId = pkg.replace(".", "")
                    val appArray = appsObj.optJSONArray(pkg)
                    val items = if (appArray != null) {
                        (0 until appArray.length()).mapNotNull { appArray.optJSONObject(it) }
                    } else {
                        listOfNotNull(appsObj.optJSONObject(pkg))
                    }

                    for (item in items) {
                        val backupId = item.optString("backupId").takeIf { it.isNotBlank() } ?: "default"
                        val tag = item.optString("backupTag").takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
                        val provider = item.optString("provider", "OneDrive")
                        val cloudDir = resolveCloudDir(provider, userEmail, existingCloudV1)

                        val existingApp = existingCloudV1?.optJSONObject(cloudDir)
                            ?.optJSONObject("tags")
                            ?.optJSONObject(tag)
                            ?.optJSONObject("apps")
                            ?.optJSONObject(sanitizedAppId)

                        if (existingApp?.has(backupId) == true) {
                            totalAlreadyExisting++
                            continue
                        }

                        val rtdbPayload = formatDiscoveredAppForRtdb(pkg, item)
                        val ok = syncAppMetadata(dbUrl, uid, cloudDir, tag, pkg, backupId, rtdbPayload, idToken)
                        if (ok) totalSynced++ else {
                            totalFailed++
                            errors.add("Failed to sync cloud backup $pkg ($backupId)")
                        }
                    }
                }
            }

            val foldersObj = cacheJson.optJSONObject("folders")
            if (foldersObj != null) {
                foldersObj.keys().forEach { fid ->
                    val fItem = foldersObj.optJSONObject(fid) ?: return@forEach
                    val folderId = fItem.optString("id", fid)
                    val tag = fItem.optString("backupTag").takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
                    val provider = fItem.optString("provider", "OneDrive")
                    val cloudDir = resolveCloudDir(provider, userEmail, existingCloudV1)

                    val existingFolder = existingCloudV1?.optJSONObject(cloudDir)
                        ?.optJSONObject("tags")
                        ?.optJSONObject(tag)
                        ?.optJSONObject("folders")

                    if (existingFolder?.has(folderId) == true) {
                        totalAlreadyExisting++
                        return@forEach
                    }
                    val ok = syncFolderMetadata(dbUrl, uid, cloudDir, tag, folderId, fItem, idToken)
                    if (ok) totalSynced++ else {
                        totalFailed++
                        errors.add("Failed to sync cloud folder $folderId")
                    }
                }
            }
        }

        // 3. Clean up legacy /users/<uid>/backups node if present
        deleteLegacyBackupsNode(dbUrl, uid, idToken)

        Log.i(TAG, "[FirebaseSync] Finished sync: $totalSynced newly synced, $totalAlreadyExisting already existing, $totalFailed failed")
        return SyncResult(
            totalSynced = totalSynced,
            totalAlreadyExisting = totalAlreadyExisting,
            totalFailed = totalFailed,
            errors = errors
        )
    }

    private fun formatDiscoveredAppForRtdb(pkgName: String, json: JSONObject, fallbackBackupId: String = "default"): JSONObject {
        val sanitized = pkgName.replace(".", "")
        val backupId = json.optString("backupId", fallbackBackupId)
        val now = System.currentTimeMillis()
        val dateBackup = json.optLong("dateBackup", json.optLong("dateBackupUpdated", now))

        return JSONObject().apply {
            put("appId", sanitized)
            put("packageName", pkgName)
            put("name", json.optString("appName", json.optString("name", pkgName)))
            put("versionCode", json.optLong("versionCode", 1L))
            put("versionName", json.optString("versionName", "1.0"))
            put("dateBackup", dateBackup)
            put("backupTag", json.optString("backupTag", "DEFAULT"))
            put("minSBVersionCodeRequired", 580L)
            put("keyVersion", 1)

            json.optString("apkLink").takeIf { it.isNotBlank() }?.let { put("apkLink", it) }
            put("apkSize", json.optLong("apkSize", json.optLong("apkBackupSize", 0L)))
            put("apkBackupDate", json.optLong("apkBackupDate", dateBackup))

            json.optString("dataLink").takeIf { it.isNotBlank() }?.let { put("dataLink", it) }
            put("dataSize", json.optLong("dataSize", json.optLong("dataBackupSize", 0L)))
            put("dataBackupDate", json.optLong("dataBackupDate", dateBackup))

            json.optString("extDataLink").takeIf { it.isNotBlank() }?.let { put("extDataLink", it) }
            put("extDataSize", json.optLong("extDataSize", json.optLong("extDataBackupSize", 0L)))
            put("extDataBackupDate", json.optLong("extDataBackupDate", dateBackup))

            json.optString("splitsLink").takeIf { it.isNotBlank() }?.let { put("splitsLink", it) }
            put("splitsSize", json.optLong("splitsSize", json.optLong("splitsBackupSize", 0L)))
            put("splitsBackupDate", json.optLong("splitsBackupDate", dateBackup))

            val extraLink = json.optString("extraLink").takeIf { it.isNotBlank() } ?: json.optString("specialDataLink").takeIf { it.isNotBlank() }
            extraLink?.let { put("specialDataLink", it) }
            val extraSize = json.optLong("extraSize", json.optLong("specialDataSize", 0L))
            if (extraSize > 0L) put("specialDataSize", extraSize)

            json.optString("ssaid").takeIf { it.isNotBlank() }?.let { put("ssaid", it) }
            json.optString("permissionStatesCsv").takeIf { it.isNotBlank() }?.let { put("permissionStatesCsv", it) }
            json.optString("notificationPolicyXml").takeIf { it.isNotBlank() }?.let { put("notificationPolicyXml", it) }

            // Encryption attributes if present
            if (json.optBoolean("dataEncrypted", false)) {
                put("dataEncrypted", true)
                json.optString("dataEncryptionMethod").takeIf { it.isNotBlank() }?.let { put("dataEncryptionMethod", it) }
                json.optString("dataPasswordHash").takeIf { it.isNotBlank() }?.let { put("dataPasswordHash", it) }
            }
            if (json.optBoolean("extDataEncrypted", false)) {
                put("extDataEncrypted", true)
                json.optString("extDataEncryptionMethod").takeIf { it.isNotBlank() }?.let { put("extDataEncryptionMethod", it) }
                json.optString("extDataPasswordHash").takeIf { it.isNotBlank() }?.let { put("extDataPasswordHash", it) }
            }
            json.optString("installerPackage").takeIf { it.isNotBlank() }?.let { put("installerPackage", it) }
            put("stability", json.optInt("stability", 0))
        }
    }

    /**
     * Pushes app backup metadata directly to configured cloud providers (WebDAV, Nextcloud, S3, etc.)
     * without writing to Firebase RTDB.
     */
    fun syncAppMetadataToCloudProviders(
        context: Context,
        pkgName: String,
        backupId: String,
        metadataJson: JSONObject,
        uid: String = BackupMigratorEngine.SWIFT_BACKUP_ANONYMOUS_UID
    ): Boolean = attempt("sync $pkgName ($backupId) metadata to cloud storage providers", silent = true) {
        val tag = metadataJson.optString("backupTag").takeIf { it.isNotBlank() && it != "DEFAULT" }
            ?: run {
                val sp = context.getSharedPreferences("org.swiftapps.swiftbackup_preferences", Context.MODE_PRIVATE)
                val connectedCloud = sp.getString("connected_cloud_type", null)
                (if (connectedCloud != null) sp.getString("${connectedCloud}_cloud_backup_tag", null) else null)
                    ?: sp.getString("google_drive_cloud_backup_tag", null)
                    ?: sp.getString("cloud_backup_tag", null)
                    ?: "DEFAULT"
            }
        metadataJson.put("backupTag", tag)

        val accountHash = BackupMigratorEngine.computeAccountHash(uid)

        // 1. Direct index record JSON for instant cloud discovery without reconstruction
        val rawJson = metadataJson.toString(2)
        listOf(
            "$pkgName.meta ($tag) (id-$backupId)",
            "$pkgName.json ($tag) (id-$backupId)",
            "SwiftBackup/accounts/$accountHash/backups/apps/local/$pkgName/$backupId/$pkgName.json"
        ).forEach { path ->
            CloudScannerRegistry.uploadTextToActiveProviders(context, path, rawJson)
        }

        // 2. Encrypted XML metadata for parity and multi-device migration
        val key = BackupCrypto.deriveConcealKey(uid)
        val encUid = BackupCrypto.concealEncrypt(uid, key)
        val encMeta = BackupCrypto.concealEncrypt(metadataJson.toString(), key)
        val xmlContent = "v1:::$encUid:::$encMeta"

        listOf(
            "SwiftBackup/accounts/$accountHash/backups/apps/local/$pkgName/$backupId/$pkgName.xml",
            "$pkgName.xml ($tag) (id-$backupId)"
        ).forEach { path ->
            CloudScannerRegistry.uploadTextToActiveProviders(context, path, xmlContent)
        }

        true
    } ?: false
}
