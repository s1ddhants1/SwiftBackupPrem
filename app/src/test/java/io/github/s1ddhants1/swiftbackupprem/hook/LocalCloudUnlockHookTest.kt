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
        // In this test runner, the stack trace does not contain intro or the dummy watcher class
        assertFalse(LocalCloudUnlockHook.shouldSkipIsAnonymousSpoof("com.dummy.WatcherClass"))
    }
}
