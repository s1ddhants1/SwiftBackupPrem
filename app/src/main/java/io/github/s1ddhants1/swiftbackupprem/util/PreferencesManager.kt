package io.github.s1ddhants1.swiftbackupprem.util

import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.model.SbpConfig
import kotlin.reflect.KProperty

@Stable
class PreferencesManager(
    private val prefs: SharedPreferences? = null,
    private val isDynamic: Boolean = false,
    private val backupPrefs: SharedPreferences? = null
) {
    private class Preference<T>(
        private val isDynamic: Boolean,
        private val key: String,
        private val defaultValue: T,
        private val getter: (key: String, defaultValue: T) -> T,
        private val setter: (key: String, newValue: T) -> Unit
    ) {
        var value by mutableStateOf(getter(key, defaultValue))
            private set

        operator fun getValue(thisRef: Any?, property: KProperty<*>): T =
            if (isDynamic) getter(key, defaultValue) else value

        operator fun setValue(thisRef: Any?, property: KProperty<*>, newValue: T) {
            value = newValue
            setter(key, newValue)
        }
    }

    private fun getString(key: String, defaultValue: String) = prefs?.getString(key, defaultValue) ?: defaultValue
    private fun getBoolean(key: String, defaultValue: Boolean) = prefs?.getBoolean(key, defaultValue) ?: defaultValue

    private fun putString(key: String, value: String?) {
        attempt("save preference string $key", silent = true) {
            prefs?.edit(commit = true) { putString(key, value) }
            backupPrefs?.edit(commit = true) { putString(key, value) }
        }
    }
    private fun putBoolean(key: String, value: Boolean) {
        attempt("save preference boolean $key", silent = true) {
            prefs?.edit(commit = true) { putBoolean(key, value) }
            backupPrefs?.edit(commit = true) { putBoolean(key, value) }
        }
    }

    private fun stringPreference(key: String) =
        Preference(isDynamic, key, "", ::getString, ::putString)

    private fun booleanPreference(key: String, defaultValue: Boolean = false) =
        Preference(isDynamic, key, defaultValue, ::getBoolean, ::putBoolean)

    var googleAppId by stringPreference(Consts.googleAppId)
    var googleApiKey by stringPreference(Consts.googleApiKey)
    var firebaseDatabaseUrl by stringPreference(Consts.firebaseDatabaseUrl)
    var gcmDefaultSenderId by stringPreference(Consts.gcmDefaultSenderId)
    var googleStorageBucket by stringPreference(Consts.googleStorageBucket)
    var projectId by stringPreference(Consts.projectId)
    var clientId by stringPreference(Consts.oauthClientId)

    var enablePremium by booleanPreference("enable_premium", true)
    var disableTelemetry by booleanPreference("disable_telemetry", true)
    var enableCloudDiscovery by booleanPreference("enable_cloud_discovery", false)
    var enableGoogleDriveScope by booleanPreference("enable_google_drive_scope", false)
    var enableSnapshotInjection by booleanPreference("enable_snapshot_injection", false)
    var enableBackupRebuilder by booleanPreference("enable_backup_rebuilder", false)
    var syncMetadataToFirebase by booleanPreference("sync_metadata_to_firebase", false)
    var unlockLocalCloudFeatures by booleanPreference("unlock_local_cloud_features", false)
    var customFirebaseApp by booleanPreference("custom_firebase_app")

    fun toConfig(): SbpConfig = SbpConfig(
        enablePremium = enablePremium,
        disableTelemetry = disableTelemetry,
        enableCloudDiscovery = enableCloudDiscovery,
        enableGoogleDriveScope = enableGoogleDriveScope,
        enableSnapshotInjection = enableSnapshotInjection,
        enableBackupRebuilder = enableBackupRebuilder,
        syncMetadataToFirebase = syncMetadataToFirebase,
        unlockLocalCloudFeatures = unlockLocalCloudFeatures,
        customFirebaseApp = customFirebaseApp,
        googleAppId = googleAppId,
        googleApiKey = googleApiKey,
        firebaseDatabaseUrl = firebaseDatabaseUrl,
        gcmDefaultSenderId = gcmDefaultSenderId,
        googleStorageBucket = googleStorageBucket,
        projectId = projectId,
        clientId = clientId
    )

    fun applyConfig(config: SbpConfig) {
        enablePremium = config.enablePremium
        disableTelemetry = config.disableTelemetry
        unlockLocalCloudFeatures = config.unlockLocalCloudFeatures
        customFirebaseApp = config.customFirebaseApp
        val canUseCloud = config.customFirebaseApp || config.unlockLocalCloudFeatures
        enableCloudDiscovery = if (canUseCloud) config.enableCloudDiscovery else false
        enableGoogleDriveScope = if (config.customFirebaseApp) config.enableGoogleDriveScope else false
        enableSnapshotInjection = if (canUseCloud && config.enableCloudDiscovery) config.enableSnapshotInjection else false
        enableBackupRebuilder = if (canUseCloud) config.enableBackupRebuilder else false
        syncMetadataToFirebase = if (canUseCloud) config.syncMetadataToFirebase else false
        googleAppId = config.googleAppId
        googleApiKey = config.googleApiKey
        firebaseDatabaseUrl = config.firebaseDatabaseUrl
        gcmDefaultSenderId = config.gcmDefaultSenderId
        googleStorageBucket = config.googleStorageBucket
        projectId = config.projectId
        clientId = config.clientId
    }
}
