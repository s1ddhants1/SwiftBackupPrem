package io.github.s1ddhants1.swiftbackupprem.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SbpConfigTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Test
    fun defaultValuesAreAsExpected() {
        val config = SbpConfig()
        assertTrue(config.enablePremium)
        assertTrue(config.disableTelemetry)
        assertFalse(config.enableGoogleDriveScope)
        assertFalse(config.enableCloudDiscovery)
        assertFalse(config.enableSnapshotInjection)
        assertFalse(config.enableBackupRebuilder)
        assertFalse(config.syncMetadataToFirebase)
        assertFalse(config.customFirebaseApp)
        assertEquals("", config.googleAppId)
        assertEquals("", config.googleApiKey)
        assertEquals("", config.firebaseDatabaseUrl)
        assertEquals("", config.gcmDefaultSenderId)
        assertEquals("", config.googleStorageBucket)
        assertEquals("", config.projectId)
        assertEquals("", config.clientId)
        assertFalse(config.isCompleteFirebaseConfig)
    }

    @Test
    fun isCompleteFirebaseConfigReturnsTrueWhenAllRequiredFieldsPresent() {
        val config = SbpConfig(
            googleAppId = "1:758023045078:android:4dea22835138c6e2ef2e77",
            googleApiKey = "AIzaSyAw7Q_SSjMMC3_SCkmnYN3S2uXmGqglzlc",
            firebaseDatabaseUrl = "https://swiftbackup-personal-adead-default-rtdb.firebaseio.com",
            gcmDefaultSenderId = "758023045078",
            projectId = "swiftbackup-personal-adead",
            clientId = "758023045078-7k1rddvuv4r31dh69fm0qpnf183528in.apps.googleusercontent.com"
        )
        assertTrue(config.isCompleteFirebaseConfig)
    }

    @Test
    fun isCompleteFirebaseConfigReturnsFalseWhenAnyRequiredFieldIsBlankOrInvalid() {
        val configBlank = SbpConfig(
            googleAppId = "1:758023045078:android:4dea22835138c6e2ef2e77",
            googleApiKey = "",
            firebaseDatabaseUrl = "https://swiftbackup-personal-adead-default-rtdb.firebaseio.com",
            gcmDefaultSenderId = "758023045078",
            projectId = "swiftbackup-personal-adead",
            clientId = "758023045078-7k1rddvuv4r31dh69fm0qpnf183528in.apps.googleusercontent.com"
        )
        assertFalse(configBlank.isCompleteFirebaseConfig)

        val configInvalid = SbpConfig(
            googleAppId = "1:758023045078:android:4dea22835138c6e2ef2e77",
            googleApiKey = "invalid-key",
            firebaseDatabaseUrl = "https://swiftbackup-personal-adead-default-rtdb.firebaseio.com",
            gcmDefaultSenderId = "758023045078",
            projectId = "swiftbackup-personal-adead",
            clientId = "758023045078-7k1rddvuv4r31dh69fm0qpnf183528in.apps.googleusercontent.com"
        )
        assertFalse(configInvalid.isCompleteFirebaseConfig)
    }

    @Test
    fun serializationAndDeserializationRoundTripMatches() {
        val original = SbpConfig(
            enablePremium = true,
            disableTelemetry = false,
            enableCloudDiscovery = true,
            customFirebaseApp = true,
            googleAppId = "app-id-123",
            googleApiKey = "api-key-456",
            firebaseDatabaseUrl = "https://db.firebaseio.com",
            gcmDefaultSenderId = "999",
            googleStorageBucket = "bucket.appspot.com",
            projectId = "my-project",
            clientId = "client-777"
        )

        val serialized = json.encodeToString(SbpConfig.serializer(), original)
        val deserialized = json.decodeFromString(SbpConfig.serializer(), serialized)

        assertEquals(original, deserialized)
    }
}
