package io.github.s1ddhants1.swiftbackupprem.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseConfigValidatorTest {

    @Test
    fun testValidUserExampleCredentials() {
        val projectId = "swiftbackup-personal-adead"
        val databaseUrl = "https://swiftbackup-personal-adead-default-rtdb.firebaseio.com"
        val appId = "1:758023045078:android:4dea22835138c6e2ef2e77"
        val apiKey = "AIzaSyAw7Q_SSjMMC3_SCkmnYN3S2uXmGqglzlc"
        val senderId = "758023045078"
        val clientId = "758023045078-7k1rddvuv4r31dh69fm0qpnf183528in.apps.googleusercontent.com"
        val storageBucket = "swiftbackup-personal-adead.firebasestorage.app"

        assertTrue(FirebaseConfigValidator.isValidProjectId(projectId))
        assertTrue(FirebaseConfigValidator.isValidDatabaseUrl(databaseUrl))
        assertTrue(FirebaseConfigValidator.isValidAppId(appId))
        assertTrue(FirebaseConfigValidator.isValidApiKey(apiKey))
        assertTrue(FirebaseConfigValidator.isValidSenderId(senderId))
        assertTrue(FirebaseConfigValidator.isValidClientId(clientId))
        assertTrue(FirebaseConfigValidator.isValidStorageBucket(storageBucket))

        assertTrue(
            FirebaseConfigValidator.isValidConfig(
                projectId = projectId,
                databaseUrl = databaseUrl,
                appId = appId,
                apiKey = apiKey,
                senderId = senderId,
                clientId = clientId,
                storageBucket = storageBucket
            )
        )
    }

    @Test
    fun testProjectIdValidation() {
        assertTrue(FirebaseConfigValidator.isValidProjectId("swift-backup-31751"))
        assertTrue(FirebaseConfigValidator.isValidProjectId("my-app-123"))
        assertTrue(FirebaseConfigValidator.isValidProjectId("test-project"))

        // Invalid cases
        assertFalse(FirebaseConfigValidator.isValidProjectId(""))
        assertFalse(FirebaseConfigValidator.isValidProjectId("abc")) // too short
        assertFalse(FirebaseConfigValidator.isValidProjectId("-invalid-start"))
        assertFalse(FirebaseConfigValidator.isValidProjectId("invalid-end-"))
        assertFalse(FirebaseConfigValidator.isValidProjectId("UPPERCASE_NOT_ALLOWED"))
    }

    @Test
    fun testDatabaseUrlValidation() {
        assertTrue(FirebaseConfigValidator.isValidDatabaseUrl("https://my-app.firebaseio.com"))
        assertTrue(FirebaseConfigValidator.isValidDatabaseUrl("https://my-app.firebaseio.com/"))
        assertTrue(FirebaseConfigValidator.isValidDatabaseUrl("https://proj-default-rtdb.europe-west1.firebasedatabase.app"))
        assertTrue(FirebaseConfigValidator.isValidDatabaseUrl("https://proj-default-rtdb.europe-west1.firebasedatabase.app/"))

        // Invalid cases
        assertFalse(FirebaseConfigValidator.isValidDatabaseUrl(""))
        assertFalse(FirebaseConfigValidator.isValidDatabaseUrl("http://my-app.firebaseio.com")) // must be https
        assertFalse(FirebaseConfigValidator.isValidDatabaseUrl("https://example.com"))
        assertFalse(FirebaseConfigValidator.isValidDatabaseUrl("abc"))
    }

    @Test
    fun testAppIdValidation() {
        assertTrue(FirebaseConfigValidator.isValidAppId("1:65312358122:android:ea39a9e3952e6522"))
        assertTrue(FirebaseConfigValidator.isValidAppId("1:758023045078:android:4dea22835138c6e2ef2e77"))

        // Invalid cases
        assertFalse(FirebaseConfigValidator.isValidAppId(""))
        assertFalse(FirebaseConfigValidator.isValidAppId("abc"))
        assertFalse(FirebaseConfigValidator.isValidAppId("1:65312358122:ios:ea39a9e3952e6522")) // iOS not supported
        assertFalse(FirebaseConfigValidator.isValidAppId("2:65312358122:android:ea39a9e3952e6522"))
    }

    @Test
    fun testApiKeyValidation() {
        assertTrue(FirebaseConfigValidator.isValidApiKey("AIzaSyAw7Q_SSjMMC3_SCkmnYN3S2uXmGqglzlc"))
        assertTrue(FirebaseConfigValidator.isValidApiKey("AIzaSyD-1234567890abcdef1234567890abcde"))

        // Invalid cases
        assertFalse(FirebaseConfigValidator.isValidApiKey(""))
        assertFalse(FirebaseConfigValidator.isValidApiKey("abc"))
        assertFalse(FirebaseConfigValidator.isValidApiKey("AIza123")) // too short
        assertFalse(FirebaseConfigValidator.isValidApiKey("BIzaSyAw7Q_SSjMMC3_SCkmnYN3S2uXmGqglzlc")) // does not start with AIza
    }

    @Test
    fun testSenderIdValidation() {
        assertTrue(FirebaseConfigValidator.isValidSenderId("758023045078"))
        assertTrue(FirebaseConfigValidator.isValidSenderId("65312358122"))

        // Invalid cases
        assertFalse(FirebaseConfigValidator.isValidSenderId(""))
        assertFalse(FirebaseConfigValidator.isValidSenderId("abc"))
        assertFalse(FirebaseConfigValidator.isValidSenderId("123")) // too short (< 6 digits)
    }

    @Test
    fun testClientIdValidation() {
        assertTrue(FirebaseConfigValidator.isValidClientId("758023045078-7k1rddvuv4r31dh69fm0qpnf183528in.apps.googleusercontent.com"))
        assertTrue(FirebaseConfigValidator.isValidClientId("123456789-abcdef.apps.googleusercontent.com"))

        // Invalid cases
        assertFalse(FirebaseConfigValidator.isValidClientId(""))
        assertFalse(FirebaseConfigValidator.isValidClientId("abc"))
        assertFalse(FirebaseConfigValidator.isValidClientId("758023045078.apps.googleusercontent.com")) // missing hash
        assertFalse(FirebaseConfigValidator.isValidClientId("758023045078-hash.google.com"))
    }

    @Test
    fun testStorageBucketValidation() {
        // Blank is valid for optional bucket
        assertTrue(FirebaseConfigValidator.isValidStorageBucket(""))
        assertTrue(FirebaseConfigValidator.isValidStorageBucket("   "))

        // Valid bucket names
        assertTrue(FirebaseConfigValidator.isValidStorageBucket("swiftbackup-personal-adead.firebasestorage.app"))
        assertTrue(FirebaseConfigValidator.isValidStorageBucket("my-app.appspot.com"))
        assertTrue(FirebaseConfigValidator.isValidStorageBucket("custom-bucket-name"))

        // Invalid cases
        assertFalse(FirebaseConfigValidator.isValidStorageBucket("ab")) // too short
        assertFalse(FirebaseConfigValidator.isValidStorageBucket("-invalid-start"))
    }
}
