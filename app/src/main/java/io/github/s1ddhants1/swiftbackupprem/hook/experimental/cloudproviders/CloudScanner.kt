package io.github.s1ddhants1.swiftbackupprem.hook.experimental.cloudproviders

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.Keep

@Keep
data class CloudFileItem(
    val id: String,
    val name: String,
    val size: Long = 0L,
    val timestamp: Long = 0L,
    val thumbnailLink: String? = null,
    val provider: String = "Generic",
    val customDownloadUrl: String? = null
)

@Keep
interface CloudScanner {
    val providerName: String
    fun isConfigured(context: Context, prefs: SharedPreferences): Boolean
    fun listFiles(context: Context, prefs: SharedPreferences): List<CloudFileItem>
    fun downloadFileText(context: Context, prefs: SharedPreferences, fileItem: CloudFileItem): String?
    fun downloadByteRange(
        context: Context,
        prefs: SharedPreferences,
        fileItem: CloudFileItem,
        startByte: Long,
        endByte: Long
    ): ByteArray? = null

    fun uploadFileText(
        context: Context,
        prefs: SharedPreferences,
        remoteRelativePath: String,
        content: String
    ): Boolean = false

    fun uploadFile(
        context: Context,
        prefs: SharedPreferences,
        remoteRelativePath: String,
        file: java.io.File
    ): Boolean = false
}
