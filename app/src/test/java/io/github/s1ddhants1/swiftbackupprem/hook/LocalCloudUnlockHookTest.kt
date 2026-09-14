package io.github.s1ddhants1.swiftbackupprem.hook

import io.github.s1ddhants1.swiftbackupprem.util.AppUtils
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.regex.Pattern

class LocalCloudUnlockHookTest {

    @Test
    fun localCloudUnlockHookImplementsHookHandler() {
        val handler: Any = LocalCloudUnlockHook
        assertTrue(handler is HookHandler)
    }

    @Test
    fun testRegexExtractionFromRtdbPaths() {
        val appPkgPattern = Pattern.compile("apps/([^/]+)/")
        val backupIdPattern = Pattern.compile("apps/[^/]+/([^/?&#]+)")

        val path1 = "https://example.firebaseio.com/cloud_v1/users/anon_uid/apps/com.dv.adm/20260821-232918-NK"
        val matcherPkg1 = appPkgPattern.matcher(path1)
        assertTrue(matcherPkg1.find())
        val pkg1 = matcherPkg1.group(1)
        assertEquals("com.dv.adm", pkg1)
        assertTrue(AppUtils.isValidPackageName(pkg1 ?: ""))

        val matcherBackupId1 = backupIdPattern.matcher(path1)
        assertTrue(matcherBackupId1.find())
        assertEquals("20260821-232918-NK", matcherBackupId1.group(1))

        val path2 = "/cloud_v1/users/anon_uid/apps/io.github.samolego.canta/"
        val matcherPkg2 = appPkgPattern.matcher(path2)
        assertTrue(matcherPkg2.find())
        val pkg2 = matcherPkg2.group(1)
        assertEquals("io.github.samolego.canta", pkg2)
        assertTrue(AppUtils.isValidPackageName(pkg2 ?: ""))

        val matcherBackupId2 = backupIdPattern.matcher(path2)
        assertFalse(matcherBackupId2.find())
    }

    @Test
    fun testInvalidPackageNamesRejected() {
        assertFalse(AppUtils.isValidPackageName(""))
        assertFalse(AppUtils.isValidPackageName("invalid"))
        assertFalse(AppUtils.isValidPackageName("123.456"))
        assertFalse(AppUtils.isValidPackageName("com..app"))
        assertTrue(AppUtils.isValidPackageName("com.example.app"))
        assertTrue(AppUtils.isValidPackageName("org.swiftapps.swiftbackup"))
    }

    @Test
    fun testPayloadPackageAndBackupIdResolution() {
        val payloadWithExplicitFields = JSONObject().apply {
            put("packageName", "com.whatsapp")
            put("backupId", "backup-20260908")
        }

        assertEquals("com.whatsapp", payloadWithExplicitFields.optString("packageName"))
        assertEquals("backup-20260908", payloadWithExplicitFields.optString("backupId"))

        val payloadWithoutFields = JSONObject().apply {
            put("totalSize", 123456)
        }

        assertTrue(payloadWithoutFields.optString("packageName").isBlank())
        assertTrue(payloadWithoutFields.optString("backupId").isBlank())
    }

    @Test
    fun testPreferencesToggleUnlocksLocalCloud() {
        val prefs = PreferencesManager(null)
        assertFalse(prefs.unlockLocalCloudFeatures)

        prefs.unlockLocalCloudFeatures = true
        assertTrue(prefs.unlockLocalCloudFeatures)

        val config = prefs.toConfig()
        assertTrue(config.unlockLocalCloudFeatures)
    }

    @Test
    fun testDirectIndexRecordFilenameMatching() {
        val appRegex = Pattern.compile("^(.*?)\\.([a-z]+)\\s+\\((.*?)\\)\\s+\\(id-(.*?)\\)$")

        val metaName = "com.dv.adm.meta (CPH2573) (id-20260908-194213-IR)"
        val metaMatcher = appRegex.matcher(metaName)
        assertTrue(metaMatcher.matches())
        assertEquals("com.dv.adm", metaMatcher.group(1))
        assertEquals("meta", metaMatcher.group(2))
        assertEquals("CPH2573", metaMatcher.group(3))
        assertEquals("20260908-194213-IR", metaMatcher.group(4))

        val jsonName = "com.dv.adm.json (CPH2573) (id-20260908-194213-IR)"
        val jsonMatcher = appRegex.matcher(jsonName)
        assertTrue(jsonMatcher.matches())
        assertEquals("com.dv.adm", jsonMatcher.group(1))
        assertEquals("json", jsonMatcher.group(2))
        assertEquals("CPH2573", jsonMatcher.group(3))
        assertEquals("20260908-194213-IR", jsonMatcher.group(4))
    }

    @Test
    fun testResolvedTargetsIncludesFirebaseWatcherClass() {
        val targets = ResolvedTargets(firebaseWatcherClass = String::class.java)
        assertNotNull(targets.firebaseWatcherClass)
        assertEquals(String::class.java, targets.firebaseWatcherClass)
    }

    @Test
    fun testShouldSkipIsAnonymousSpoofNormalCaller() {
        assertFalse(LocalCloudUnlockHook.shouldSkipIsAnonymousSpoof("com.dummy.WatcherClass"))
    }

    abstract class DummyResult(val success: Boolean)
    class DummySuccess : DummyResult(true) {
        override fun toString(): String = "Success"
    }
    class DummyError(val error: Throwable) : DummyResult(false) {
        override fun toString(): String = "Error(failure)"
    }

    class DummyDatabaseError(val code: Int, val message: String)
    class DummyObfuscatedError(val err: DummyDatabaseError) : DummyResult(false) {
        override fun toString(): String = "Error(error=$err)"
    }
    class DummyObfuscatedSuccess : DummyResult(true) {
        override fun toString(): String = "Success"
    }

    @Test
    fun testSuccessClassAndInstanceValidation() {
        assertTrue(LocalCloudUnlockHook.isSuccessClass(DummySuccess::class.java, DummyResult::class.java))
        assertFalse(LocalCloudUnlockHook.isSuccessClass(DummyError::class.java, DummyResult::class.java))
        assertFalse(LocalCloudUnlockHook.isSuccessClass(DummyObfuscatedError::class.java, DummyResult::class.java))
        assertTrue(LocalCloudUnlockHook.isSuccessClass(DummyObfuscatedSuccess::class.java, DummyResult::class.java))

        val successInst = DummySuccess()
        val errorInst = DummyError(RuntimeException("test"))
        val obfErrorInst = DummyObfuscatedError(DummyDatabaseError(-11, "Anonymous user not allowed"))
        val obfSuccessInst = DummyObfuscatedSuccess()

        assertTrue(LocalCloudUnlockHook.isSuccessInstance(successInst))
        assertFalse(LocalCloudUnlockHook.isSuccessInstance(errorInst))
        assertFalse(LocalCloudUnlockHook.isSuccessInstance(obfErrorInst))
        assertTrue(LocalCloudUnlockHook.isSuccessInstance(obfSuccessInst))
    }

    @Test
    fun testPurchaseVerificationLeafPathReturnsBoolean() {
        val leafPath = "https://swift-backup-31751.firebaseio.com/purchase_verifications/d58b0944415a4889d7f11aa95fbeca50/AQK8OqW4gtD36Xte/DRxZA4zBTU1sr0y0N5jq+IeqAxKIILVpxc="
        val segments = io.github.s1ddhants1.swiftbackupprem.hook.experimental.CloudDatabaseManager.extractPathSegments(leafPath)
        val pvIndex = segments.indexOf("purchase_verifications")
        assertTrue(pvIndex != -1)
        assertTrue(segments.size >= pvIndex + 3)
    }

    @Test
    fun testNaturalPurchaseVerificationJsonResolution() {
        val root = org.json.JSONObject().apply {
            put("purchase_verifications", org.json.JSONObject().apply {
                put("test_uid", org.json.JSONObject().apply {
                    put("validity", true)
                    put("encrypted_key", true)
                })
            })
        }
        val segmentsLeaf = listOf("purchase_verifications", "test_uid", "validity")
        val nodeLeaf = io.github.s1ddhants1.swiftbackupprem.hook.experimental.CloudDatabaseManager.resolvePathInJson(root, segmentsLeaf)
        assertEquals(true, nodeLeaf)

        val segmentsParent = listOf("purchase_verifications", "test_uid")
        val nodeParent = io.github.s1ddhants1.swiftbackupprem.hook.experimental.CloudDatabaseManager.resolvePathInJson(root, segmentsParent)
        assertTrue(nodeParent is org.json.JSONObject)
        val convertedParent = io.github.s1ddhants1.swiftbackupprem.hook.experimental.CloudDatabaseManager.jsonToValue(nodeParent)
        assertTrue(convertedParent is Map<*, *>)
        assertEquals(true, (convertedParent as Map<*, *>)["validity"])
    }

    abstract class DummyParentResult {
        class DummyParentSuccess(val ok: Boolean) : DummyParentResult() {
            companion object {
                @JvmField
                val INSTANCE = DummyParentSuccess(true)
            }
            override fun toString(): String = "Success"
        }
        class DummyParentError(val t: Throwable) : DummyParentResult() {
            companion object {
                @JvmField
                val ERROR_INSTANCE = DummyParentError(RuntimeException("Anonymous user not allowed"))
            }
            override fun toString(): String = "Error"
        }
    }

    @Test
    fun testAuthenticSuccessInstanceDoesNotReturnError() {
        val inst = LocalCloudUnlockHook.getAuthenticSuccessInstance(DummyParentResult::class.java)
        assertNotNull(inst)
        assertTrue(inst is DummyParentResult.DummyParentSuccess)
        assertFalse(inst is DummyParentResult.DummyParentError)
    }

    class DummyAnonUser(
        val email: String = "anonymous@swiftbackup.app",
        val providerId: String = "anonymous",
        val isAnonymous: Boolean = true,
        val uid: String = "d58b0944415a4889d7f11aa95fbeca50"
    )

    class DummyGoogleUser(
        val email: String = "user@gmail.com",
        val providerId: String = "google.com",
        val isAnonymous: Boolean = false,
        val uid: String = "real_google_uid_12345"
    )

    @Test
    fun testIsAnonymousUserInstanceDetection() {
        assertTrue(LocalCloudUnlockHook.isAnonymousUserInstance(DummyAnonUser()))
        assertFalse(LocalCloudUnlockHook.isAnonymousUserInstance(DummyGoogleUser()))
        assertFalse(LocalCloudUnlockHook.isAnonymousUserInstance(null))
    }

    @Test
    fun testShouldEnforceLocalCloudWhenFeatureDisabled() {
        val prefs = PreferencesManager(null)
        prefs.unlockLocalCloudFeatures = false
        prefs.customFirebaseApp = false
        assertFalse(LocalCloudUnlockHook.shouldEnforceLocalCloud(prefs, null))

        prefs.customFirebaseApp = true
        assertFalse(LocalCloudUnlockHook.shouldEnforceLocalCloud(prefs, null))
    }

    @Test
    fun testShouldEnforceLocalCloudWhenCustomFirebaseDisabled() {
        val prefs = PreferencesManager(null)
        prefs.unlockLocalCloudFeatures = true
        prefs.customFirebaseApp = false
        assertTrue(LocalCloudUnlockHook.shouldEnforceLocalCloud(prefs, null))
    }

    @Test
    fun testShouldEnforceLocalCloudWhenCustomFirebaseEnabledWithoutSignIn() {
        val prefs = PreferencesManager(null)
        prefs.unlockLocalCloudFeatures = true
        prefs.customFirebaseApp = true
        LocalCloudUnlockHook.clearAuthCache()
        assertTrue(LocalCloudUnlockHook.shouldEnforceLocalCloud(prefs, null))
    }
}

