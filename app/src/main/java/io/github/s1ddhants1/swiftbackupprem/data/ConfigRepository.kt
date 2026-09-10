package io.github.s1ddhants1.swiftbackupprem.data

import android.content.ContentResolver
import android.net.Uri
import io.github.s1ddhants1.swiftbackupprem.model.SbpConfig
import io.github.s1ddhants1.swiftbackupprem.util.GoogleServicesJson
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
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
            val jsonStr = contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().use { it.readText() }
            } ?: error("Could not open selected import file")

            parseConfig(jsonStr, prefs)
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
            "clientId"
        ).any { rawJson.has(it) }

        if (!isGoogleServices && !hasSbpKeys) {
            throw IllegalArgumentException("Unrecognized or invalid configuration file format")
        }

        if (isGoogleServices) {
            prefs.customFirebaseApp = true
            GoogleServicesJson.applyToPrefs(rawJson, prefs)
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
            unlockLocalCloudFeatures = if (rawJson.has("unlockLocalCloudFeatures")) rawJson.optBoolean("unlockLocalCloudFeatures", decoded.unlockLocalCloudFeatures) else decoded.unlockLocalCloudFeatures
        )

        prefs.applyConfig(finalConfig)
        return finalConfig
    }
}
