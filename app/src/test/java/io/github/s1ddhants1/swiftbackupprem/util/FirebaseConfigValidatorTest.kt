package io.github.s1ddhants1.swiftbackupprem.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseConfigValidatorTest {

    @Test
    fun testValidCredentials() {
        val projectId = "dummy-firebase-project"
        val databaseUrl = "https://dummy-firebase-project-default-rtdb.firebaseio.com"
        val appId = "1:123456789012:android:abcdef0123456789"
        val apiKey = "AIzaSyD_FakeApiKeyForTestingPurposes123"
        val senderId = "123456789012"
        val clientId = "123456789012-androidclient1234567890abcdef.apps.googleusercontent.com"
        val storageBucket = "dummy-firebase-project.firebasestorage.app"

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
        assertTrue(FirebaseConfigValidator.isValidAppId("1:123456789012:android:abcdef0123456789"))

        // Invalid cases
        assertFalse(FirebaseConfigValidator.isValidAppId(""))
        assertFalse(FirebaseConfigValidator.isValidAppId("abc"))
        assertFalse(FirebaseConfigValidator.isValidAppId("1:65312358122:ios:ea39a9e3952e6522")) // iOS not supported
        assertFalse(FirebaseConfigValidator.isValidAppId("2:65312358122:android:ea39a9e3952e6522"))
    }

    @Test
    fun testApiKeyValidation() {
        assertTrue(FirebaseConfigValidator.isValidApiKey("AIzaSyD_FakeApiKeyForTestingPurposes123"))
        assertTrue(FirebaseConfigValidator.isValidApiKey("AIzaSyD-1234567890abcdef1234567890abcde"))

        // Invalid cases
        assertFalse(FirebaseConfigValidator.isValidApiKey(""))
        assertFalse(FirebaseConfigValidator.isValidApiKey("abc"))
        assertFalse(FirebaseConfigValidator.isValidApiKey("AIza123")) // too short
        assertFalse(FirebaseConfigValidator.isValidApiKey("BIzaSyD_FakeApiKeyForTestingPurposes123")) // does not start with AIza
    }

    @Test
    fun testSenderIdValidation() {
        assertTrue(FirebaseConfigValidator.isValidSenderId("123456789012"))
        assertTrue(FirebaseConfigValidator.isValidSenderId("65312358122"))

        // Invalid cases
        assertFalse(FirebaseConfigValidator.isValidSenderId(""))
        assertFalse(FirebaseConfigValidator.isValidSenderId("abc"))
        assertFalse(FirebaseConfigValidator.isValidSenderId("123")) // too short (< 6 digits)
    }

    @Test
    fun testClientIdValidation() {
        assertTrue(FirebaseConfigValidator.isValidClientId("123456789012-androidclient1234567890abcdef.apps.googleusercontent.com"))
        assertTrue(FirebaseConfigValidator.isValidClientId("123456789-abcdef.apps.googleusercontent.com"))

        // Invalid cases
        assertFalse(FirebaseConfigValidator.isValidClientId(""))
        assertFalse(FirebaseConfigValidator.isValidClientId("abc"))
        assertFalse(FirebaseConfigValidator.isValidClientId("123456789012.apps.googleusercontent.com")) // missing hash
        assertFalse(FirebaseConfigValidator.isValidClientId("123456789012-hash.google.com"))
    }

    @Test
    fun testStorageBucketValidation() {
        // Blank is valid for optional bucket
        assertTrue(FirebaseConfigValidator.isValidStorageBucket(""))
        assertTrue(FirebaseConfigValidator.isValidStorageBucket("   "))

        // Valid bucket names
        assertTrue(FirebaseConfigValidator.isValidStorageBucket("dummy-firebase-project.firebasestorage.app"))
        assertTrue(FirebaseConfigValidator.isValidStorageBucket("my-app.appspot.com"))
        assertTrue(FirebaseConfigValidator.isValidStorageBucket("custom-bucket-name"))

        // Invalid cases
        assertFalse(FirebaseConfigValidator.isValidStorageBucket("ab")) // too short
        assertFalse(FirebaseConfigValidator.isValidStorageBucket("-invalid-start"))
    }
}
