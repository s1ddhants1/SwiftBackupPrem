package io.github.s1ddhants1.swiftbackupprem.data

import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import org.junit.Assert.*
import org.junit.Test

class ConfigRepositoryTest {

    private val repository = ConfigRepositoryImpl()

    @Test
    fun parseConfigParsesStandardSbpJson() {
        val prefs = PreferencesManager(null)
        val json = """
            {
              "enablePremium": true,
              "disableTelemetry": false,
              "enableCloudDiscovery": true,
              "customFirebaseApp": true,
              "googleAppId": "1:888:android:999",
              "googleApiKey": "repo-api-key",
              "firebaseDatabaseUrl": "https://repo.firebaseio.com",
              "gcmDefaultSenderId": "888",
              "googleStorageBucket": "repo.appspot.com",
              "projectId": "repo-project",
              "clientId": "repo-client-id"
            }
        """.trimIndent()

        val result = repository.parseConfig(json, prefs)

        assertTrue(result.enablePremium)
        assertFalse(result.disableTelemetry)
        assertTrue(result.enableCloudDiscovery)
        assertTrue(result.customFirebaseApp)
        assertEquals("1:888:android:999", result.googleAppId)
        assertEquals("repo-api-key", result.googleApiKey)
        assertEquals("https://repo.firebaseio.com", result.firebaseDatabaseUrl)
        assertEquals("888", result.gcmDefaultSenderId)
        assertEquals("repo.appspot.com", result.googleStorageBucket)
        assertEquals("repo-project", result.projectId)
        assertEquals("repo-client-id", result.clientId)

        assertTrue(prefs.enableCloudDiscovery)
        assertEquals("repo-project", prefs.projectId)
        assertEquals("repo-client-id", prefs.clientId)
    }

    @Test
    fun parseConfigParsesGoogleServicesJsonDirectly() {
        val prefs = PreferencesManager(null)
        val json = """
            {
              "project_info": {
                "firebase_url": "https://gs.firebaseio.com",
                "project_number": "555666",
                "storage_bucket": "gs.appspot.com",
                "project_id": "gs-project"
              },
              "client": [
                {
                  "client_info": {
                    "mobilesdk_app_id": "1:555666:android:gsid"
                  },
                  "api_key": [
                    {
                      "current_key": "gs-key"
                    }
                  ]
                }
              ],
              "oauth_client_id": "gs-oauth-client"
            }
        """.trimIndent()

        val result = repository.parseConfig(json, prefs)

        assertTrue(result.customFirebaseApp)
        assertEquals("1:555666:android:gsid", result.googleAppId)
        assertEquals("gs-key", result.googleApiKey)
        assertEquals("https://gs.firebaseio.com", result.firebaseDatabaseUrl)
        assertEquals("555666", result.gcmDefaultSenderId)
        assertEquals("gs.appspot.com", result.googleStorageBucket)
        assertEquals("gs-project", result.projectId)
        assertEquals("gs-oauth-client", result.clientId)
    }

    @Test
    fun parseConfigParsesNewCloudDiscoveryAndSnapshotSettings() {
        val prefs = PreferencesManager(null)
        val json = """
            {
              "enablePremium": true,
              "disableTelemetry": true,
              "customFirebaseApp": true,
              "enableGoogleDriveScope": true,
              "enableCloudDiscovery": true,
              "enableSnapshotInjection": false,
              "enableBackupRebuilder": true,
              "syncMetadataToFirebase": true,
              "googleAppId": "1:123:android:456",
              "googleApiKey": "api-key",
              "firebaseDatabaseUrl": "https://test.firebaseio.com",
              "gcmDefaultSenderId": "123",
              "googleStorageBucket": "test.appspot.com",
              "projectId": "test-project",
              "clientId": "test-client-id"
            }
        """.trimIndent()

        val result = repository.parseConfig(json, prefs)

        assertTrue(result.enableGoogleDriveScope)
        assertTrue(result.enableCloudDiscovery)
        assertFalse(result.enableSnapshotInjection)
        assertTrue(result.enableBackupRebuilder)
        assertTrue(result.syncMetadataToFirebase)

        assertTrue(prefs.enableGoogleDriveScope)
        assertTrue(prefs.enableCloudDiscovery)
        assertFalse(prefs.enableSnapshotInjection)
        assertTrue(prefs.enableBackupRebuilder)
        assertTrue(prefs.syncMetadataToFirebase)
    }

    @Test
    fun parseConfigParsesLegacyMinimalConfigWithoutNewKeys() {
        val prefs = PreferencesManager(null)
        val legacyJson = """
            {
              "enablePremium": true,
              "suppressTelemetry": true,
              "customFirebaseApp": true,
              "googleAppId": "1:999:android:old",
              "projectId": "old-project"
            }
        """.trimIndent()

        val result = repository.parseConfig(legacyJson, prefs)

        assertTrue(result.enablePremium)
        assertTrue(result.disableTelemetry)
        assertTrue(result.customFirebaseApp)
        assertEquals("1:999:android:old", result.googleAppId)
        assertEquals("old-project", result.projectId)

        // Verifies new keys smoothly adopt default values without error
        assertFalse(result.enableGoogleDriveScope)
        assertFalse(result.enableCloudDiscovery)
        assertFalse(result.enableSnapshotInjection)
        assertFalse(result.enableBackupRebuilder)
        assertFalse(result.syncMetadataToFirebase)
        assertFalse(result.unlockLocalCloudFeatures)
    }

    @Test
    fun parseConfigParsesUnlockLocalCloudFeatures() {
        val prefs = PreferencesManager(null)
        val json = """
            {
              "enablePremium": true,
              "disableTelemetry": true,
              "unlockLocalCloudFeatures": true,
              "enableCloudDiscovery": true
            }
        """.trimIndent()

        val result = repository.parseConfig(json, prefs)

        assertTrue(result.unlockLocalCloudFeatures)
        assertTrue(result.enableCloudDiscovery)
        assertTrue(prefs.unlockLocalCloudFeatures)
        assertTrue(prefs.enableCloudDiscovery)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseConfigRejectsInvalidJsonStructure() {
        val prefs = PreferencesManager(null)
        val json = """
            {
              "invalid_key": "some_value"
            }
        """.trimIndent()

        repository.parseConfig(json, prefs)
    }
}
