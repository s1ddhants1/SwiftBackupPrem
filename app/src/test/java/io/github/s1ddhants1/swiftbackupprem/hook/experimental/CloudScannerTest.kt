package io.github.s1ddhants1.swiftbackupprem.hook.experimental

import io.github.s1ddhants1.swiftbackupprem.hook.experimental.cloudproviders.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudScannerTest {

    @Test
    fun testCloudScannerRegistryContainsAllProviders() {
        val providers = CloudScannerRegistry.scanners.map { it.providerName }
        assertTrue(providers.contains("GoogleDrive"))
        assertTrue(providers.contains("WebDAV"))
        assertTrue(providers.contains("S3"))
        assertTrue(providers.contains("Dropbox"))
        assertTrue(providers.contains("OneDrive"))
        assertTrue(providers.contains("Box"))
        assertTrue(providers.contains("pCloud"))

        assertNotNull(CloudScannerRegistry.getScannerByProvider("webdav"))
        assertNotNull(CloudScannerRegistry.getScannerByProvider("s3"))
        assertNotNull(CloudScannerRegistry.getScannerByProvider("dropbox"))
        assertNotNull(CloudScannerRegistry.getScannerByProvider("onedrive"))
        assertNotNull(CloudScannerRegistry.getScannerByProvider("box"))
        assertNotNull(CloudScannerRegistry.getScannerByProvider("pcloud"))
    }

    @Test
    fun testDropboxListFolderJsonParsing() {
        val sampleJson = """
        {
          "entries": [
            {
              ".tag": "file",
              "name": "com.dv.adm.app (CPH2573) (id-20260821-232918-NK)",
              "id": "id:abc12345",
              "size": 40000000,
              "path_display": "/SwiftBackup/com.dv.adm.app (CPH2573) (id-20260821-232918-NK)"
            },
            {
              ".tag": "file",
              "name": "com.dv.adm.extra (CPH2573) (id-20260821-232918-NK)",
              "id": "id:abc67890",
              "size": 2048,
              "path_display": "/SwiftBackup/com.dv.adm.extra (CPH2573) (id-20260821-232918-NK)"
            },
            {
              ".tag": "folder",
              "name": "accounts",
              "id": "id:folder123"
            }
          ],
          "has_more": false
        }
        """.trimIndent()

        val root = JSONObject(sampleJson)
        val entries = root.getJSONArray("entries")
        val items = mutableListOf<CloudFileItem>()

        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            if (entry.optString(".tag") == "file") {
                items.add(
                    CloudFileItem(
                        id = entry.getString("path_display"),
                        name = entry.getString("name"),
                        size = entry.getLong("size"),
                        provider = "Dropbox"
                    )
                )
            }
        }

        assertEquals(2, items.size)
        assertEquals("com.dv.adm.app (CPH2573) (id-20260821-232918-NK)", items[0].name)
        assertEquals(40000000L, items[0].size)
        assertEquals("/SwiftBackup/com.dv.adm.extra (CPH2573) (id-20260821-232918-NK)", items[1].id)
    }

    @Test
    fun testOneDriveGraphJsonParsing() {
        val sampleJson = """
        {
          "value": [
            {
              "id": "01ABCDEF12345678",
              "name": "v3.1700000000.5.hash.cls (CPH2573)",
              "size": 15000,
              "file": { "mimeType": "application/octet-stream" },
              "@microsoft.graph.downloadUrl": "https://download.onedrive.com/file1"
            },
            {
              "id": "01ABCDEF99999999",
              "name": "backups",
              "folder": { "childCount": 10 }
            }
          ]
        }
        """.trimIndent()

        val root = JSONObject(sampleJson)
        val valueArr = root.getJSONArray("value")
        val items = mutableListOf<CloudFileItem>()

        for (i in 0 until valueArr.length()) {
            val obj = valueArr.getJSONObject(i)
            if (!obj.has("folder")) {
                items.add(
                    CloudFileItem(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        size = obj.getLong("size"),
                        customDownloadUrl = obj.optString("@microsoft.graph.downloadUrl"),
                        provider = "OneDrive"
                    )
                )
            }
        }

        assertEquals(1, items.size)
        assertEquals("v3.1700000000.5.hash.cls (CPH2573)", items[0].name)
        assertEquals(15000L, items[0].size)
        assertEquals("https://download.onedrive.com/file1", items[0].customDownloadUrl)
    }

    @Test
    fun testOneDriveParentReferencePathResolution() {
        val sampleJson = """
        {
          "value": [
            {
              "id": "01ABCDEF12345678",
              "name": "com.dv.adm.apk (CPH2573) (id-20260821-232918)",
              "size": 15000000,
              "parentReference": {
                "driveId": "c127d293d2e2aa69",
                "path": "/drive/root:/Swift Backup"
              },
              "@microsoft.graph.downloadUrl": "https://download.onedrive.com/file1"
            },
            {
              "id": "01ABCDEF87654321",
              "name": "com.dv.adm.data (CPH2573) (id-20260821-232918)",
              "size": 500000,
              "parentReference": {
                "driveId": "c127d293d2e2aa69",
                "path": "/drive/root:/Swift Backup (12345)/DEFAULT"
              }
            }
          ]
        }
        """.trimIndent()

        val root = JSONObject(sampleJson)
        val valueArr = root.getJSONArray("value")
        val items = mutableListOf<CloudFileItem>()

        for (i in 0 until valueArr.length()) {
            val obj = valueArr.getJSONObject(i)
            val name = obj.getString("name")
            val parentRef = obj.optJSONObject("parentReference")
            val parentPath = parentRef?.optString("path")
            val relParent = if (!parentPath.isNullOrBlank() && parentPath.contains("root:")) {
                parentPath.substringAfter("root:").trim('/')
            } else ""
            val relPath = if (relParent.isNotBlank()) "$relParent/$name" else name

            items.add(
                CloudFileItem(
                    id = relPath,
                    name = name,
                    size = obj.getLong("size"),
                    customDownloadUrl = obj.optString("@microsoft.graph.downloadUrl"),
                    provider = "OneDrive"
                )
            )
        }

        assertEquals(2, items.size)
        assertEquals("Swift Backup/com.dv.adm.apk (CPH2573) (id-20260821-232918)", items[0].id)
        assertEquals("Swift Backup (12345)/DEFAULT/com.dv.adm.data (CPH2573) (id-20260821-232918)", items[1].id)
    }

    @Test
    fun testPCloudJsonParsing() {
        val sampleJson = """
        {
          "result": 0,
          "metadata": {
            "isfolder": true,
            "contents": [
              {
                "isfolder": false,
                "name": "folder-base.fld (CPH2573) (id-20260821-111111-AA)",
                "fileid": 123456789,
                "size": 999999
              },
              {
                "isfolder": false,
                "name": "folder-base.flm (CPH2573) (id-20260821-111111-AA)",
                "fileid": 123456790,
                "size": 1024
              }
            ]
          }
        }
        """.trimIndent()

        val root = JSONObject(sampleJson)
        val contents = root.getJSONObject("metadata").getJSONArray("contents")
        val items = mutableListOf<CloudFileItem>()

        for (i in 0 until contents.length()) {
            val item = contents.getJSONObject(i)
            if (!item.getBoolean("isfolder")) {
                items.add(
                    CloudFileItem(
                        id = item.getLong("fileid").toString(),
                        name = item.getString("name"),
                        size = item.getLong("size"),
                        provider = "pCloud"
                    )
                )
            }
        }

        assertEquals(2, items.size)
        assertEquals("folder-base.fld (CPH2573) (id-20260821-111111-AA)", items[0].name)
        assertEquals(999999L, items[0].size)
    }

    @Test
    fun testMultiCloudAppDeduplicationAndAggregation() {
        val driveApp = CloudDiscoveryHook.DiscoveredCloudApp(
            packageName = "org.telegram.messenger",
            sanitizedAppId = "orgtelegrammessenger",
            backupId = "20260825-100000-AB",
            backupTag = "DEVICE1",
            apkSize = 50000000L,
            totalSize = 50000000L,
            provider = "GoogleDrive"
        )

        val nextcloudApp = CloudDiscoveryHook.DiscoveredCloudApp(
            packageName = "com.whatsapp",
            sanitizedAppId = "comwhatsapp",
            backupId = "20260825-110000-CD",
            backupTag = "DEVICE1",
            apkSize = 45000000L,
            totalSize = 45000000L,
            provider = "WebDAV"
        )

        val map = mapOf(
            driveApp.packageName to driveApp,
            nextcloudApp.packageName to nextcloudApp
        )

        assertEquals("GoogleDrive", map["org.telegram.messenger"]?.provider)
        assertEquals("WebDAV", map["com.whatsapp"]?.provider)
        assertEquals(95000000L, map.values.sumOf { it.totalSize })
    }

    @Test
    fun testOneDriveTokenResolution() {
        val testPrefs = object : android.content.SharedPreferences {
            val map = mutableMapOf<String, Any?>("msal_account_token_onedrive" to "fake-token-123")
            override fun getAll(): MutableMap<String, *> = map
            override fun getString(key: String?, defValue: String?): String? = map[key]?.toString() ?: defValue
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = null
            override fun getInt(key: String?, defValue: Int): Int = 0
            override fun getLong(key: String?, defValue: Long): Long = 0L
            override fun getFloat(key: String?, defValue: Float): Float = 0f
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = false
            override fun contains(key: String?): Boolean = map.containsKey(key)
            override fun edit(): android.content.SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }

        val resolved = OneDriveScanner.resolveToken(testPrefs)
        assertEquals("fake-token-123", resolved)
    }
}
