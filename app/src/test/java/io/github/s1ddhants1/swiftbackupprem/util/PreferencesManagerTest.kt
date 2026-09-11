package io.github.s1ddhants1.swiftbackupprem.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesManagerTest {

    @Test
    fun defaultValuesAreCorrectWhenPreferencesAreNull() {
        val prefs = PreferencesManager(null)

        assertTrue(prefs.enablePremium)
        assertTrue(prefs.disableTelemetry)
        assertFalse(prefs.enableGoogleDriveScope)
        assertFalse(prefs.enableCloudDiscovery)
        assertFalse(prefs.enableSnapshotInjection)
        assertFalse(prefs.enableBackupRebuilder)
        assertFalse(prefs.syncMetadataToFirebase)
        assertFalse(prefs.unlockLocalCloudFeatures)
        assertFalse(prefs.customFirebaseApp)
        assertFalse(prefs.firebaseSetupFinished)
        assertEquals("", prefs.googleAppId)
        assertEquals("", prefs.googleApiKey)
        assertEquals("", prefs.firebaseDatabaseUrl)
        assertEquals("", prefs.gcmDefaultSenderId)
        assertEquals("", prefs.googleStorageBucket)
        assertEquals("", prefs.projectId)
        assertEquals("", prefs.clientId)
    }

    @Test
    fun mutatingPreferencesUpdatesValuesInMemory() {
        val prefs = PreferencesManager(null)

        prefs.enablePremium = false
        prefs.disableTelemetry = false
        prefs.unlockLocalCloudFeatures = true
        prefs.customFirebaseApp = true
        prefs.firebaseSetupFinished = true
        prefs.googleAppId = "test-app-id"
        prefs.googleApiKey = "test-api-key"
        prefs.firebaseDatabaseUrl = "https://test.firebaseio.com"
        prefs.gcmDefaultSenderId = "987654"
        prefs.googleStorageBucket = "test.appspot.com"
        prefs.projectId = "test-project"
        prefs.clientId = "test-client-id"

        assertFalse(prefs.enablePremium)
        assertFalse(prefs.disableTelemetry)
        assertTrue(prefs.unlockLocalCloudFeatures)
        assertTrue(prefs.customFirebaseApp)
        assertTrue(prefs.firebaseSetupFinished)
        assertEquals("test-app-id", prefs.googleAppId)
        assertEquals("test-api-key", prefs.googleApiKey)
        assertEquals("https://test.firebaseio.com", prefs.firebaseDatabaseUrl)
        assertEquals("987654", prefs.gcmDefaultSenderId)
        assertEquals("test.appspot.com", prefs.googleStorageBucket)
        assertEquals("test-project", prefs.projectId)
        assertEquals("test-client-id", prefs.clientId)
    }

    @Test
    fun mutatingPreferencesWritesToBothPrimaryAndBackupPreferences() {
        val primary = FakeSharedPreferences()
        val backup = FakeSharedPreferences()
        val prefs = PreferencesManager(primary, backupPrefs = backup)

        prefs.enablePremium = false
        prefs.projectId = "synced-project"

        assertEquals(false, primary.getBoolean("enable_premium", true))
        assertEquals(false, backup.getBoolean("enable_premium", true))
        assertEquals("synced-project", primary.getString("project_id", ""))
        assertEquals("synced-project", backup.getString("project_id", ""))
    }

    @Test
    fun applyConfigDisablesAllMigrationSettingsWhenCustomFirebaseIsFalse() {
        val prefs = PreferencesManager(null)
        val config = io.github.s1ddhants1.swiftbackupprem.model.SbpConfig(
            customFirebaseApp = false,
            enableCloudDiscovery = true,
            enableGoogleDriveScope = true,
            enableSnapshotInjection = true,
            enableBackupRebuilder = true,
            syncMetadataToFirebase = true
        )

        prefs.applyConfig(config)

        assertFalse(prefs.customFirebaseApp)
        assertFalse(prefs.enableCloudDiscovery)
        assertFalse(prefs.enableGoogleDriveScope)
        assertFalse(prefs.enableSnapshotInjection)
        assertFalse(prefs.enableBackupRebuilder)
        assertFalse(prefs.syncMetadataToFirebase)
    }

    @Test
    fun applyConfigPreservesCloudDiscoveryWhenUnlockLocalCloudFeaturesIsTrue() {
        val prefs = PreferencesManager(null)
        val config = io.github.s1ddhants1.swiftbackupprem.model.SbpConfig(
            customFirebaseApp = false,
            unlockLocalCloudFeatures = true,
            enableCloudDiscovery = true,
            enableGoogleDriveScope = true,
            enableSnapshotInjection = true,
            enableBackupRebuilder = true,
            syncMetadataToFirebase = true
        )

        prefs.applyConfig(config)

        assertFalse(prefs.customFirebaseApp)
        assertTrue(prefs.unlockLocalCloudFeatures)
        assertTrue(prefs.enableCloudDiscovery)
        assertFalse(prefs.enableGoogleDriveScope)
        assertTrue(prefs.enableSnapshotInjection)
        assertTrue(prefs.enableBackupRebuilder)
        assertTrue(prefs.syncMetadataToFirebase)
    }

    private class FakeSharedPreferences : android.content.SharedPreferences {
        val map = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = map
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = map[key] as? Set<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = FakeEditor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val sp: FakeSharedPreferences) : android.content.SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor { temp[key] = value; return this }
            override fun putStringSet(key: String, values: Set<String>?): android.content.SharedPreferences.Editor { temp[key] = values; return this }
            override fun putInt(key: String, value: Int): android.content.SharedPreferences.Editor { temp[key] = value; return this }
            override fun putLong(key: String, value: Long): android.content.SharedPreferences.Editor { temp[key] = value; return this }
            override fun putFloat(key: String, value: Float): android.content.SharedPreferences.Editor { temp[key] = value; return this }
            override fun putBoolean(key: String, value: Boolean): android.content.SharedPreferences.Editor { temp[key] = value; return this }
            override fun remove(key: String): android.content.SharedPreferences.Editor { temp.remove(key); return this }
            override fun clear(): android.content.SharedPreferences.Editor { temp.clear(); return this }
            override fun commit(): Boolean { sp.map.putAll(temp); return true }
            override fun apply() { sp.map.putAll(temp) }
        }
    }
}
