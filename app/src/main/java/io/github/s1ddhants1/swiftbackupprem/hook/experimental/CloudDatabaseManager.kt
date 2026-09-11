package io.github.s1ddhants1.swiftbackupprem.hook.experimental

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import androidx.annotation.Keep
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.hook.ResolvedTargets
import io.github.s1ddhants1.swiftbackupprem.hook.experimental.cloudproviders.CloudScannerRegistry
import io.github.s1ddhants1.swiftbackupprem.util.AppUtils
import io.github.s1ddhants1.swiftbackupprem.util.BackupCrypto
import io.github.s1ddhants1.swiftbackupprem.util.BackupMigratorEngine
import io.github.s1ddhants1.swiftbackupprem.util.FirebaseSyncEngine
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.regex.Pattern

/**
 * Manages the spoofed Firebase Realtime Database JSON on the cloud drive and local cache.
 * Provides bidirectional synchronization between Swift Backup's RTDB operations
 * and the database JSON document stored in the cloud drive.
 */
@Keep
object CloudDatabaseManager {

    private const val TAG = Consts.TAG
    const val CANONICAL_DB_FILE_NAME = "cloud_discovered_cache.json"

    @Volatile
    var currentDbJson: JSONObject? = null

    private val bgExecutor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "SBP-CloudDbSync").apply { isDaemon = true }
        }
    }

    fun getLocalDbFile(): File {
        val dir = File(Environment.getExternalStorageDirectory(), "SwiftBackup")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, CANONICAL_DB_FILE_NAME)
    }

    fun getPrimaryUid(): String? {
        val db = currentDbJson ?: return null
        val users = db.optJSONObject("users") ?: return null
        val keys = users.keys()
        return if (keys.hasNext()) keys.next() else null
    }

    @SuppressLint("SdCardPath")
    fun loadLocalDb(context: Context? = null): JSONObject? = attempt("loadLocalDb", silent = true) {
        val canonical = getLocalDbFile()
        val candidateFiles = listOfNotNull(
            canonical,
            File("/sdcard/SwiftBackup", CANONICAL_DB_FILE_NAME),
            File("/storage/emulated/0/SwiftBackup", CANONICAL_DB_FILE_NAME),
            context?.filesDir?.let { File(it, CANONICAL_DB_FILE_NAME) }
        )

        for (file in candidateFiles) {
            if (file.exists() && file.canRead()) {
                val text = file.readText(StandardCharsets.UTF_8).trim()
                if (text.startsWith("{") && text.endsWith("}")) {
                    val parsed = JSONObject(text)
                    currentDbJson = parsed
                    CloudDiscoveryHook.loadDatabaseJson(parsed)
                    Log.i(TAG, "[CloudDb] Loaded local database JSON from ${file.absolutePath}")
                    return@attempt parsed
                }
            }
        }
        null
    }

    fun saveLocalDb(json: JSONObject) {
        attempt("saveLocalDb", silent = true) {
            val file = getLocalDbFile()
            val text = json.toString(2)
            file.writeText(text, StandardCharsets.UTF_8)
            file.setReadable(true, false)

            currentDbJson = json
            Log.d(TAG, "[CloudDb] Saved local database JSON to ${file.absolutePath} (${file.length()} bytes)")
        }
    }

    fun syncDbToCloud(context: Context) {
        bgExecutor.execute {
            attempt("syncDbToCloud", silent = true) {
                val json = currentDbJson ?: loadLocalDb(context) ?: return@attempt
                val content = json.toString(2)
                Log.d(TAG, "[CloudDb] Uploading $CANONICAL_DB_FILE_NAME to active cloud providers...")
                CloudScannerRegistry.uploadTextToActiveProviders(context, CANONICAL_DB_FILE_NAME, content)
            }
        }
    }

    fun syncDbFromCloud(context: Context): JSONObject? = attempt("syncDbFromCloud", silent = true) {
        val providerResults = CloudScannerRegistry.scanAllConfiguredProviders(context)
        for (prov in providerResults) {
            val dbItem = prov.items.firstOrNull { it.name.equals(CANONICAL_DB_FILE_NAME, ignoreCase = true) }
            if (dbItem != null) {
                val sp: SharedPreferences = attempt("get sp", silent = true) {
                    context.getSharedPreferences("org.swiftapps.swiftbackup_preferences", Context.MODE_PRIVATE)
                } ?: run {
                    @Suppress("DEPRECATION")
                    android.preference.PreferenceManager.getDefaultSharedPreferences(context)
                }
                val text = prov.scanner.downloadFileText(context, sp, dbItem)
                if (!text.isNullOrBlank() && text.startsWith("{")) {
                    val parsed = JSONObject(text)
                    currentDbJson = parsed
                    saveLocalDb(parsed)
                    CloudDiscoveryHook.loadDatabaseJson(parsed)
                    Log.i(TAG, "[CloudDb] Successfully downloaded ${dbItem.name} from cloud provider: ${prov.scanner.providerName}")
                    return@attempt parsed
                }
            }
        }
        null
    }

    fun ensureDb(
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ): JSONObject {
        val sp: SharedPreferences = attempt("get sp", silent = true) {
            context.getSharedPreferences("org.swiftapps.swiftbackup_preferences", Context.MODE_PRIVATE)
        } ?: run {
            @Suppress("DEPRECATION")
            android.preference.PreferenceManager.getDefaultSharedPreferences(context)
        }

        val existing = currentDbJson ?: loadLocalDb(context) ?: syncDbFromCloud(context)
        if (existing != null) {
            if (reconcileAppSettings(existing, sp)) {
                saveLocalDb(existing)
                syncDbToCloud(context)
            }
            currentDbJson = existing
            return existing
        }

        val constructed = buildDatabaseFromDiscovered(context, classLoader, targets, prefs)
        currentDbJson = constructed
        saveLocalDb(constructed)
        syncDbToCloud(context)
        return constructed
    }

    fun buildAppSettings(sp: SharedPreferences, connectedCloud: String? = null): JSONObject {
        val appSettings = JSONObject()

        val stratStr = sp.getString("apps_multiple_backups_strategy", null)
        if (!stratStr.isNullOrBlank()) {
            val parsedStrat = attempt("parse apps_multiple_backups_strategy", silent = true) {
                JSONObject(stratStr)
            }
            if (parsedStrat != null) {
                appSettings.put("appsMultipleBackupStrategy", parsedStrat)
            } else {
                appSettings.put("appsMultipleBackupStrategy", JSONObject().put("typeInt", 0))
            }
        } else {
            appSettings.put("appsMultipleBackupStrategy", JSONObject().put("typeInt", 0))
        }

        val cloud = connectedCloud ?: sp.getString("connected_cloud_type", null)
        if (!cloud.isNullOrBlank()) {
            appSettings.put("cloudConnection", cloud)
        }

        val themeMode = sp.getInt("app_theme_mode", 3)
        appSettings.put("themeModeId", themeMode)

        val useAmoled = if (sp.contains("use_amoled_theme")) {
            sp.getBoolean("use_amoled_theme", false)
        } else if (sp.contains("amoled_black_theme")) {
            sp.getBoolean("amoled_black_theme", false)
        } else {
            false
        }
        if (useAmoled) {
            appSettings.put("useAmoledTheme", true)
        }

        if (sp.contains("multithreaded_downloads_chunk_count")) {
            val chunks = sp.getInt("multithreaded_downloads_chunk_count", -1)
            if (chunks > 0) {
                appSettings.put("multiThreadChunksCount", chunks)
            }
        }

        if (sp.contains("parallel_cloud_transfers")) {
            appSettings.put("parallelCloudTransfers", sp.getBoolean("parallel_cloud_transfers", false))
        }

        if (sp.contains("dynamic_colors")) {
            appSettings.put("dynamicColors", sp.getBoolean("dynamic_colors", true))
        }

        if (sp.contains("show_system_apps")) {
            appSettings.put("showSystemApps", sp.getBoolean("show_system_apps", false))
        }

        if (sp.contains("backup_app_cache")) {
            appSettings.put("isAppCacheBackupReq", sp.getBoolean("backup_app_cache", false))
        }

        if (sp.contains("in_place_apk_downgrades")) {
            appSettings.put("inPlaceApkDowngrades", sp.getBoolean("in_place_apk_downgrades", false))
        }

        if (sp.contains("restore_ssaids")) {
            appSettings.put("restoreSsaids", sp.getBoolean("restore_ssaids", false))
        }

        if (sp.contains("play_notification_sounds")) {
            appSettings.put("playNotificationSounds", sp.getBoolean("play_notification_sounds", true))
        }

        if (sp.contains("compression_level_apps")) {
            val lvl = sp.getInt("compression_level_apps", -1)
            if (lvl >= 0) appSettings.put("appsCompressionLevel", lvl)
        }
        if (sp.contains("compression_level_folders")) {
            val lvl = sp.getInt("compression_level_folders", -1)
            if (lvl >= 0) appSettings.put("foldersCompressionLevel", lvl)
        }

        return appSettings
    }

    fun reconcileAppSettings(db: JSONObject, sp: SharedPreferences): Boolean {
        val users = db.optJSONObject("users") ?: return false
        val connectedCloud = sp.getString("connected_cloud_type", null)
        val freshSettings = buildAppSettings(sp, connectedCloud)

        var changed = false
        val keys = users.keys()
        while (keys.hasNext()) {
            val uid = keys.next()
            val userObj = users.optJSONObject(uid) ?: continue
            val currentSettings = userObj.optJSONObject("appSettings")
            if (currentSettings == null) {
                userObj.put("appSettings", freshSettings)
                changed = true
                continue
            }

            val stratStr = sp.getString("apps_multiple_backups_strategy", null)
            val currentStrat = currentSettings.optJSONObject("appsMultipleBackupStrategy")
            if (stratStr.isNullOrBlank()) {
                if (currentStrat == null || currentStrat.has("maxNumOfBackups") || currentStrat.optInt("typeInt", 0) != 0) {
                    currentSettings.put("appsMultipleBackupStrategy", JSONObject().put("typeInt", 0))
                    changed = true
                }
            } else {
                val parsed = attempt("parse strat", silent = true) { JSONObject(stratStr) }
                if (parsed != null && (currentStrat == null || currentStrat.toString() != parsed.toString())) {
                    currentSettings.put("appsMultipleBackupStrategy", parsed)
                    changed = true
                }
            }

            val freshKeys = freshSettings.keys()
            while (freshKeys.hasNext()) {
                val fk = freshKeys.next()
                if (fk == "appsMultipleBackupStrategy") continue
                val fv = freshSettings.get(fk)
                if (!currentSettings.has(fk) || currentSettings.get(fk) != fv) {
                    currentSettings.put(fk, fv)
                    changed = true
                }
            }

            if (!freshSettings.has("useAmoledTheme") && currentSettings.has("useAmoledTheme")) {
                currentSettings.remove("useAmoledTheme")
                changed = true
            }
        }
        return changed
    }

    private fun extractUserDisplayName(sp: SharedPreferences): String? {
        for ((_, v) in sp.all) {
            val str = v as? String ?: continue
            if (str.startsWith("{") && str.contains("displayName") && str.contains("email")) {
                val json = attempt("parse user json", silent = true) { JSONObject(str) }
                val name = json?.optString("displayName")
                if (!name.isNullOrBlank() && name != "Anonymous user") {
                    return name
                }
            }
        }
        return null
    }

    fun buildDatabaseFromDiscovered(
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ): JSONObject {
        val sp: SharedPreferences = attempt("get sp", silent = true) {
            context.getSharedPreferences("org.swiftapps.swiftbackup_preferences", Context.MODE_PRIVATE)
        } ?: run {
            @Suppress("DEPRECATION")
            android.preference.PreferenceManager.getDefaultSharedPreferences(context)
        }

        val uid = prefs.localAccountCustomUid.trim().takeIf { it.isNotBlank() }
            ?: getPrimaryUid()
            ?: BackupCrypto.resolveCandidateUids(context, classLoader).firstOrNull { it != BackupMigratorEngine.SWIFT_BACKUP_ANONYMOUS_UID }
            ?: "ymiQ80Ks3RStD4CEugbLgyQPCUM2"

        val connectedCloud = sp.getString("connected_cloud_type", null) ?: "google_drive"
        val cloudEmail = sp.getString("${connectedCloud}_cloud_email_address", null)
            ?: sp.getString("google_drive_cloud_email_address", null)
            ?: "local@swiftbackup.app"
        val sanitizedEmail = cloudEmail.replace(".", "").replace("/", "").replace("#", "").replace("$", "")
        val cloudDir = "${connectedCloud} ($sanitizedEmail)"

        val deviceTag = sp.getString("${connectedCloud}_cloud_backup_tag", null)
            ?: sp.getString("google_drive_cloud_backup_tag", null)
            ?: sp.getString("cloud_backup_tag", null)
            ?: "DEFAULT"

        val allApps = CloudDiscoveryHook.getAllDiscoveredApps()
        val root = JSONObject()
        val usersObj = JSONObject()
        val userObj = JSONObject()

        // 1. appSettings
        val appSettings = buildAppSettings(sp, connectedCloud)
        userObj.put("appSettings", appSettings)

        // 2. userInfo
        val appVersionCode = attempt("get app version", silent = true) {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
            }
        } ?: 620L
        val displayName = extractUserDisplayName(sp) ?: cloudEmail.substringBefore("@").ifBlank { "Local User" }
        val userInfo = JSONObject().apply {
            put("anonymous", false)
            put("currentAppVersion", appVersionCode)
            put("displayName", displayName)
            put("email", cloudEmail)
            put("id", uid)
            put("latestAppVersion", appVersionCode)
            put("photoUrl", "")
        }
        userObj.put("userInfo", userInfo)

        // 3. transactions
        val transactions = JSONObject().apply {
            val premium = JSONObject().apply {
                put("purchaseToken", "SBP_LOCAL_PREMIUM")
                put("sku", "premium")
                put("verificationTime", System.currentTimeMillis())
            }
            put("premium", premium)
        }
        userObj.put("transactions", transactions)

        // 4. cloud_v1
        val cloudV1 = JSONObject()
        val cloudDirObj = JSONObject()
        val tagsObj = JSONObject()
        val tagObj = JSONObject()

        val appsObj = JSONObject()
        allApps.groupBy { it.sanitizedAppId }.forEach { (sanitizedId, appsForPkg) ->
            val pkgObj = JSONObject()
            appsForPkg.forEach { app ->
                val backupMap = CloudDiscoveryHook.FirebaseSnapshotSynthesizer.buildSingleBackupMap(app)
                pkgObj.put(app.backupId, JSONObject(backupMap))
            }
            appsObj.put(sanitizedId, pkgObj)
        }
        tagObj.put("apps", appsObj)

        val foldersObj = JSONObject()
        CloudDiscoveryHook.discoveredFolders.forEach { (fid, folder) ->
            foldersObj.put(fid, folder.toJson())
        }
        tagObj.put("folders", foldersObj)
        tagObj.put("smsBackupsCount", CloudDiscoveryHook.discoveredSms.size)
        tagObj.put("callLogBackupsCount", CloudDiscoveryHook.discoveredCalls.size)

        tagsObj.put(deviceTag, tagObj)
        if (deviceTag != "DEFAULT") {
            tagsObj.put("DEFAULT", tagObj)
        }
        cloudDirObj.put("tags", tagsObj)

        val wallsObj = JSONObject().apply {
            put("wallsBackupCount", CloudDiscoveryHook.discoveredWalls.size)
        }
        cloudDirObj.put("walls", wallsObj)

        cloudV1.put(cloudDir, cloudDirObj)
        userObj.put("cloud_v1", cloudV1)

        usersObj.put(uid, userObj)
        root.put("users", usersObj)

        Log.i(TAG, "[CloudDb] Constructed fresh database JSON for UID $uid with ${allApps.size} apps, tag=$deviceTag")
        return root
    }

    fun extractPathSegments(rawPath: String): List<String> {
        var path = rawPath.trim()
        if (path.contains("://")) {
            val urlPart = path.substringAfter("://")
            path = urlPart.substringAfter("/", "")
        }
        path = path.substringBefore("?").substringBefore("#")
        return path.split("/")
            .map { segment ->
                try {
                    URLDecoder.decode(segment, "UTF-8").trim()
                } catch (_: Throwable) {
                    segment.trim()
                }
            }
            .filter { it.isNotEmpty() }
    }

    fun resolvePathInJson(root: JSONObject, segments: List<String>): Any? {
        if (segments.isEmpty()) return root

        var current: Any? = root
        for (i in segments.indices) {
            val segment = segments[i]
            if (current !is JSONObject) return null

            if (current.has(segment)) {
                current = current.get(segment)
                continue
            }

            val keys = current.keys().asSequence().toList()
            val matchedKey = keys.firstOrNull { it.equals(segment, ignoreCase = true) }
                ?: keys.firstOrNull { it.replace(".", "").equals(segment.replace(".", ""), ignoreCase = true) }
                ?: keys.firstOrNull { it.replace(" ", "").equals(segment.replace(" ", ""), ignoreCase = true) }
                ?: (if (i > 0 && segments[i - 1] == "users" && keys.isNotEmpty()) keys.first() else null)
                ?: (if (i > 0 && segments[i - 1] == "cloud_v1" && keys.isNotEmpty()) {
                    keys.firstOrNull { it.substringBefore(" ").equals(segment.substringBefore(" "), ignoreCase = true) }
                        ?: keys.first()
                } else null)
                ?: (if (i > 0 && segments[i - 1] == "tags" && keys.isNotEmpty()) keys.first() else null)

            if (matchedKey != null) {
                current = current.get(matchedKey)
            } else {
                return null
            }
        }
        return current
    }

    fun jsonToValue(json: Any?): Any? = when (json) {
        null, JSONObject.NULL -> null
        is JSONObject -> {
            val map = mutableMapOf<String, Any?>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = jsonToValue(json.opt(k))
            }
            map
        }
        is JSONArray -> {
            val list = mutableListOf<Any?>()
            for (i in 0 until json.length()) {
                list.add(jsonToValue(json.opt(i)))
            }
            list
        }
        is Boolean, is Number, is String -> json
        is Map<*, *> -> json
        is List<*> -> json
        else -> json.toString()
    }

    fun getSnapshotDataForPath(
        rawPath: String,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ): Any? {
        if (rawPath.contains(".info/connected") || rawPath.endsWith(".info/connected")) {
            return true
        }
        if (rawPath.contains("health/enabled") || rawPath.contains("appData/health")) {
            return true
        }

        val db = ensureDb(context, classLoader, targets, prefs)
        val segments = extractPathSegments(rawPath)
        val node = resolvePathInJson(db, segments)

        if (node != null) {
            val converted = jsonToValue(node)
            Log.d(TAG, "[CloudDb] Resolved path '${segments.joinToString("/")}' to ${node.javaClass.simpleName}")
            return converted
        }

        // Fallback for special subpaths if JSON path didn't resolve literally:
        val fullPathStr = segments.joinToString("/")
        if (fullPathStr.contains("smsBackupsCount")) {
            return CloudDiscoveryHook.discoveredSms.size
        }
        if (fullPathStr.contains("callLogBackupsCount")) {
            return CloudDiscoveryHook.discoveredCalls.size
        }
        val pkgMatch = Pattern.compile("apps/([^/?&#]+)").matcher(fullPathStr)
        if (pkgMatch.find()) {
            val targetPkg = pkgMatch.group(1) ?: ""
            val matchingApps = CloudDiscoveryHook.findMatchingBackups(targetPkg)
            if (matchingApps.isNotEmpty()) {
                return CloudDiscoveryHook.FirebaseSnapshotSynthesizer.buildMetadataMap(matchingApps)
            }
        }
        if (fullPathStr.endsWith("apps") || fullPathStr.endsWith("apps/")) {
            val allApps = CloudDiscoveryHook.getAllDiscoveredApps()
            return allApps.groupBy { it.sanitizedAppId }.mapValues { (_, appsForPkg) ->
                CloudDiscoveryHook.FirebaseSnapshotSynthesizer.buildMetadataMap(appsForPkg)
            }
        }
        if (fullPathStr.endsWith("folders") || fullPathStr.endsWith("folders/")) {
            return CloudDiscoveryHook.discoveredFolders.mapValues { (_, folder) -> folder.toJson() }
        }

        return emptyMap<String, Any>()
    }

    fun updateDbFromWrite(
        rawPath: String,
        payload: Any?,
        context: Context,
        prefs: PreferencesManager
    ) {
        attempt("updateDbFromWrite", silent = true) {
            val db = currentDbJson ?: return@attempt
            val segments = extractPathSegments(rawPath)
            if (segments.isEmpty()) return@attempt

            var current = db
            for (i in 0 until segments.size - 1) {
                val seg = segments[i]
                if (!current.has(seg)) {
                    current.put(seg, JSONObject())
                }
                current = current.getJSONObject(seg)
            }

            val lastKey = segments.last()
            val jsonVal = when (payload) {
                null -> JSONObject.NULL
                is JSONObject, is JSONArray, is Boolean, is Number, is String -> payload
                is Map<*, *> -> @Suppress("UNCHECKED_CAST") JSONObject(payload as Map<String, Any?>)
                else -> {
                    val obj = JSONObject()
                    for (field in payload.javaClass.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                        try {
                            field.isAccessible = true
                            field.get(payload)?.let { obj.put(field.name.removePrefix("_"), it) }
                        } catch (_: Throwable) {}
                    }
                    obj
                }
            }

            current.put(lastKey, jsonVal)
            saveLocalDb(db)
            syncDbToCloud(context)
            Log.i(TAG, "[CloudDb] Updated database JSON at path: ${segments.joinToString("/")}")
        }
    }
}
