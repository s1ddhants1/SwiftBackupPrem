package io.github.s1ddhants1.swiftbackupprem.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleServicesJsonTest {

    @Test
    fun applyToPrefsReadsFirebaseProjectAndClientValues() {
        val prefs = PreferencesManager(null)
        val json = JSONObject(
            """
            {
              "project_info": {
                "firebase_url": "https://example.firebaseio.com",
                "project_number": "123456",
                "storage_bucket": "example.appspot.com",
                "project_id": "example"
              },
              "client": [
                {
                  "client_info": {
                    "mobilesdk_app_id": "1:123456:android:abcdef"
                  },
                  "api_key": [
                    {
                      "current_key": "api-key"
                    }
                  ]
                }
              ],
              "oauth_client_id": "oauth-client"
            }
            """.trimIndent()
        )

        GoogleServicesJson.applyToPrefs(json, prefs)

        assertEquals("1:123456:android:abcdef", prefs.googleAppId)
        assertEquals("api-key", prefs.googleApiKey)
        assertEquals("https://example.firebaseio.com", prefs.firebaseDatabaseUrl)
        assertEquals("123456", prefs.gcmDefaultSenderId)
        assertEquals("example.appspot.com", prefs.googleStorageBucket)
        assertEquals("example", prefs.projectId)
        assertEquals("oauth-client", prefs.clientId)
    }

    @Test
    fun buildFromPrefsWritesExpectedFirebaseShape() {
        val prefs = PreferencesManager(null).apply {
            googleAppId = "app-id"
            googleApiKey = "api-key"
            firebaseDatabaseUrl = "https://example.firebaseio.com"
            gcmDefaultSenderId = "123456"
            googleStorageBucket = "example.appspot.com"
            projectId = "example"
            clientId = "oauth-client"
        }

        val json = GoogleServicesJson.buildFromPrefs(prefs)
        val client = json.getJSONArray("client").getJSONObject(0)
        val projectInfo = json.getJSONObject("project_info")

        assertEquals("app-id", client.getJSONObject("client_info").getString("mobilesdk_app_id"))
        assertEquals("api-key", client.getJSONArray("api_key").getJSONObject(0).getString("current_key"))
        assertEquals("https://example.firebaseio.com", projectInfo.getString("firebase_url"))
        assertEquals("123456", projectInfo.getString("project_number"))
        assertEquals("example.appspot.com", projectInfo.getString("storage_bucket"))
        assertEquals("example", projectInfo.getString("project_id"))
        assertEquals("oauth-client", json.getString("oauth_client_id"))
    }

    @Test
    fun applyToPrefsWithOAuthClientArrayParsesAndroidClientId() {
        val prefs = PreferencesManager(null)
        val json = JSONObject(
            """
            {
              "project_info": {
                "project_number": "758023045078",
                "firebase_url": "https://swiftbackup-personal-adead-default-rtdb.firebaseio.com",
                "project_id": "swiftbackup-personal-adead",
                "storage_bucket": "swiftbackup-personal-adead.firebasestorage.app"
              },
              "client": [
                {
                  "client_info": {
                    "mobilesdk_app_id": "1:758023045078:android:4dea22835138c6e2ef2e77",
                    "android_client_info": {
                      "package_name": "org.swiftapps.swiftbackup"
                    }
                  },
                  "oauth_client": [
                    {
                      "client_id": "758023045078-7k1rddvuv4r31dh69fm0qpnf183528in.apps.googleusercontent.com",
                      "client_type": 1,
                      "android_info": {
                        "package_name": "org.swiftapps.swiftbackup",
                        "certificate_hash": "648200797d2a97e5b3dceebeb2f25b9650e52832"
                      }
                    },
                    {
                      "client_id": "758023045078-b4mkkr3spqtdn0dmkdrmust73nihj2ie.apps.googleusercontent.com",
                      "client_type": 3
                    }
                  ],
                  "api_key": [
                    {
                      "current_key": "AIzaSyAw7Q_SSjMMC3_SCkmnYN3S2uXmGqglzlc"
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        GoogleServicesJson.applyToPrefs(json, prefs)

        assertEquals("swiftbackup-personal-adead", prefs.projectId)
        assertEquals("https://swiftbackup-personal-adead-default-rtdb.firebaseio.com", prefs.firebaseDatabaseUrl)
        assertEquals("1:758023045078:android:4dea22835138c6e2ef2e77", prefs.googleAppId)
        assertEquals("AIzaSyAw7Q_SSjMMC3_SCkmnYN3S2uXmGqglzlc", prefs.googleApiKey)
        assertEquals("758023045078", prefs.gcmDefaultSenderId)
        assertEquals("758023045078-7k1rddvuv4r31dh69fm0qpnf183528in.apps.googleusercontent.com", prefs.clientId)
        assertEquals("swiftbackup-personal-adead.firebasestorage.app", prefs.googleStorageBucket)
    }
}
