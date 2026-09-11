package io.github.s1ddhants1.swiftbackupprem.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.model.SbpConfig
import io.github.s1ddhants1.swiftbackupprem.util.GoogleServicesJson
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONObject

interface ConfigRepository {
    suspend fun exportConfig(contentResolver: ContentResolver, uri: Uri, config: SbpConfig): Result<Unit>
    suspend fun importConfig(contentResolver: ContentResolver, uri: Uri, prefs: PreferencesManager): Result<SbpConfig>
    fun parseConfig(jsonStr: String, prefs: PreferencesManager): SbpConfig
}

class ConfigRepositoryImpl(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
) : ConfigRepository {

    override suspend fun exportConfig(
        contentResolver: ContentResolver,
        uri: Uri,
        config: SbpConfig
    ): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val jsonString = json.encodeToString(SbpConfig.serializer(), config)
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(jsonString.toByteArray(Charsets.UTF_8))
            } ?: error("Could not open selected export destination")
        }
    }

    override suspend fun importConfig(
        contentResolver: ContentResolver,
        uri: Uri,
        prefs: PreferencesManager
    ): Result<SbpConfig> = withContext(ioDispatcher) {
        runCatching {
            val jsonStr = readTextFromUri(contentResolver, uri)
            parseConfig(jsonStr, prefs)
        }
    }

    private fun readTextFromUri(contentResolver: ContentResolver, uri: Uri): String {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                return inputStream.bufferedReader().use { it.readText() }
            }
        } catch (t: Throwable) {
            Log.w(Consts.TAG, "Failed to read URI via ContentResolver, attempting path fallback", t)
        }

        val resolvedPath = resolvePathFromDocumentUri(uri)
        if (!resolvedPath.isNullOrBlank()) {
            val file = File(resolvedPath)
            if (file.exists() && file.canRead()) {
                try {
                    return file.readText()
                } catch (t: Throwable) {
                    Log.w(Consts.TAG, "Failed to read direct file: $resolvedPath", t)
                }
            }
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat '$resolvedPath'"))
                val text = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                if (text.isNotBlank()) {
                    return text
                }
            } catch (t: Throwable) {
                Log.w(Consts.TAG, "Failed to read file via su: $resolvedPath", t)
            }
        }

        error("Could not open selected import file")
    }

    private fun resolvePathFromDocumentUri(uri: Uri): String? {
        return try {
            if ("file".equals(uri.scheme, ignoreCase = true)) {
                return uri.path
            }
            if ("com.android.externalstorage.documents".equals(uri.authority, ignoreCase = true)) {
                val docId = DocumentsContract.getDocumentId(uri)
                if (docId.startsWith("primary:", ignoreCase = true)) {
                    return "/storage/emulated/0/" + docId.substringAfter(":")
                } else if (docId.contains(":")) {
                    return "/storage/" + docId.substringBefore(":") + "/" + docId.substringAfter(":")
                }
            }
            uri.path
        } catch (_: Throwable) {
            uri.path
        }
    }

    override fun parseConfig(jsonStr: String, prefs: PreferencesManager): SbpConfig {
        val rawJson = JSONObject(jsonStr)
        val isGoogleServices = rawJson.has("client") && rawJson.has("project_info")
        val hasSbpKeys = listOf(
            "enablePremium",
            "disableTelemetry",
            "suppressTelemetry",
            "enableDriveDiscovery",
            "enableGoogleDriveScope",
            "enableCloudDiscovery",
            "enableSnapshotInjection",
            "enableBackupRebuilder",
            "syncMetadataToFirebase",
            "unlockLocalCloudFeatures",
            "customFirebaseApp",
            "googleAppId",
            "googleApiKey",
            "firebaseDatabaseUrl",
            "gcmDefaultSenderId",
            "googleStorageBucket",
            "projectId",
            "clientId",
            "localAccountCustomUid"
        ).any { rawJson.has(it) }

        if (!isGoogleServices && !hasSbpKeys) {
            throw IllegalArgumentException("Unrecognized or invalid configuration file format")
        }

        if (isGoogleServices) {
            prefs.customFirebaseApp = true
            GoogleServicesJson.applyToPrefs(rawJson, prefs)
            prefs.firebaseSetupFinished = prefs.toConfig().isCompleteFirebaseConfig
            return prefs.toConfig()
        }

        val jsonToDecode = if (rawJson.has("suppressTelemetry") && !rawJson.has("disableTelemetry")) {
            JSONObject(jsonStr).apply {
                put("disableTelemetry", rawJson.optBoolean("suppressTelemetry", true))
            }.toString()
        } else {
            jsonStr
        }

        val base = prefs.toConfig()
        val decoded = runCatching { json.decodeFromString(SbpConfig.serializer(), jsonToDecode) }.getOrDefault(base)
        val finalConfig = decoded.copy(
            disableTelemetry = when {
                rawJson.has("disableTelemetry") -> rawJson.optBoolean("disableTelemetry", true)
                rawJson.has("suppressTelemetry") -> rawJson.optBoolean("suppressTelemetry", true)
                else -> decoded.disableTelemetry
            },
            enableGoogleDriveScope = if (rawJson.has("enableGoogleDriveScope")) rawJson.optBoolean("enableGoogleDriveScope", decoded.enableGoogleDriveScope) else decoded.enableGoogleDriveScope,
            enableCloudDiscovery = if (rawJson.has("enableCloudDiscovery")) {
                rawJson.optBoolean("enableCloudDiscovery", decoded.enableCloudDiscovery)
            } else if (rawJson.has("enableDriveDiscovery")) {
                rawJson.optBoolean("enableDriveDiscovery", decoded.enableCloudDiscovery)
            } else {
                decoded.enableCloudDiscovery
            },
            enableSnapshotInjection = if (rawJson.has("enableSnapshotInjection")) rawJson.optBoolean("enableSnapshotInjection", decoded.enableSnapshotInjection) else decoded.enableSnapshotInjection,
            enableBackupRebuilder = if (rawJson.has("enableBackupRebuilder")) rawJson.optBoolean("enableBackupRebuilder", decoded.enableBackupRebuilder) else decoded.enableBackupRebuilder,
            syncMetadataToFirebase = if (rawJson.has("syncMetadataToFirebase")) rawJson.optBoolean("syncMetadataToFirebase", decoded.syncMetadataToFirebase) else decoded.syncMetadataToFirebase,
            unlockLocalCloudFeatures = if (rawJson.has("unlockLocalCloudFeatures")) rawJson.optBoolean("unlockLocalCloudFeatures", decoded.unlockLocalCloudFeatures) else decoded.unlockLocalCloudFeatures,
            localAccountCustomUid = if (rawJson.has("localAccountCustomUid")) rawJson.optString("localAccountCustomUid", decoded.localAccountCustomUid) else decoded.localAccountCustomUid
        )

        prefs.applyConfig(finalConfig)
        return finalConfig
    }
}
