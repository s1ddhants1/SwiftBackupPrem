package io.github.s1ddhants1.swiftbackupprem.hook.experimental.cloudproviders

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.Keep
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import java.io.File

@Keep
object CloudScannerRegistry {
    private const val TAG = Consts.TAG

    val scanners: List<CloudScanner> = listOf(
        GoogleDriveScanner,
        WebDavScanner,
        S3Scanner,
        DropboxScanner,
        OneDriveScanner,
        BoxScanner,
        PCloudScanner
    )

    fun getScannerByProvider(providerName: String): CloudScanner? =
        scanners.firstOrNull { it.providerName.equals(providerName, ignoreCase = true) }

    data class DiscoveredProviderItems(
        val scanner: CloudScanner,
        val items: List<CloudFileItem>
    )

    @SuppressLint("SdCardPath")
    fun scanAllConfiguredProviders(context: Context): List<DiscoveredProviderItems> {
        val sp: SharedPreferences = attempt("get swiftbackup main prefs", silent = true) {
            context.getSharedPreferences("org.swiftapps.swiftbackup_preferences", Context.MODE_PRIVATE)
        } ?: return emptyList()

        val aggregatedPrefs = buildAggregatedPreferences(context, sp)
        val results = mutableListOf<DiscoveredProviderItems>()

        for (scanner in scanners) {
            try {
                if (scanner.isConfigured(context, aggregatedPrefs)) {
                    Log.i(TAG, "[CloudScannerRegistry] Discovered configured cloud provider: ${scanner.providerName}. Scanning files...")
                    val files = scanner.listFiles(context, aggregatedPrefs)
                    Log.i(TAG, "[CloudScannerRegistry] Provider ${scanner.providerName} returned ${files.size} items")
                    if (files.isNotEmpty()) {
                        results.add(DiscoveredProviderItems(scanner, files))
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "[CloudScannerRegistry] Error scanning provider ${scanner.providerName}: ${t.message}")
            }
        }

        return results
    }


    fun uploadToActiveProviders(
        context: Context,
        remoteRelativePath: String,
        file: File
    ): Boolean {
        val sp: SharedPreferences = attempt("get swiftbackup main prefs", silent = true) {
            context.getSharedPreferences("org.swiftapps.swiftbackup_preferences", Context.MODE_PRIVATE)
        } ?: return false
        val aggregatedPrefs = buildAggregatedPreferences(context, sp)
        var anySuccess = false
        for (scanner in scanners) {
            try {
                if (scanner.isConfigured(context, aggregatedPrefs)) {
                    val ok = scanner.uploadFile(context, aggregatedPrefs, remoteRelativePath, file)
                    if (ok) {
                        Log.i(TAG, "[CloudScannerRegistry] Successfully uploaded $remoteRelativePath to ${scanner.providerName}")
                        anySuccess = true
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "[CloudScannerRegistry] Failed to upload $remoteRelativePath to ${scanner.providerName}: ${t.message}")
            }
        }
        return anySuccess
    }

    fun uploadTextToActiveProviders(
        context: Context,
        remoteRelativePath: String,
        content: String
    ): Boolean {
        val sp: SharedPreferences = attempt("get swiftbackup main prefs", silent = true) {
            context.getSharedPreferences("org.swiftapps.swiftbackup_preferences", Context.MODE_PRIVATE)
        } ?: return false
        val aggregatedPrefs = buildAggregatedPreferences(context, sp)
        var anySuccess = false
        for (scanner in scanners) {
            try {
                if (scanner.isConfigured(context, aggregatedPrefs)) {
                    val ok = scanner.uploadFileText(context, aggregatedPrefs, remoteRelativePath, content)
                    if (ok) {
                        Log.i(TAG, "[CloudScannerRegistry] Successfully uploaded text $remoteRelativePath to ${scanner.providerName}")
                        anySuccess = true
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "[CloudScannerRegistry] Failed to upload text $remoteRelativePath to ${scanner.providerName}: ${t.message}")
            }
        }
        return anySuccess
    }

    @SuppressLint("SdCardPath")
    private fun buildAggregatedPreferences(context: Context, primaryPrefs: SharedPreferences): SharedPreferences {
        val prefsDir = File(context.filesDir?.parentFile, "shared_prefs")
        if (!prefsDir.exists() || !prefsDir.isDirectory) {
            return primaryPrefs
        }

        val allMaps = mutableMapOf<String, Any?>()
        primaryPrefs.all.forEach { (k, v) -> allMaps[k] = v }

        try {
            prefsDir.listFiles { f -> f.extension == "xml" && f.name != "org.swiftapps.swiftbackup_preferences.xml" }?.forEach { xmlFile ->
                val nameWithoutExt = xmlFile.nameWithoutSuffix(".xml")
                val secondarySp = context.getSharedPreferences(nameWithoutExt, Context.MODE_PRIVATE)
                secondarySp.all.forEach { (k, v) ->
                    if (!allMaps.containsKey(k)) {
                        allMaps[k] = v
                    }
                }
            }
        } catch (_: Throwable) {}

        return object : SharedPreferences {
            override fun getAll(): MutableMap<String, *> = allMaps
            override fun getString(key: String?, defValue: String?): String? = allMaps[key]?.toString() ?: defValue
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
                @Suppress("UNCHECKED_CAST") (allMaps[key] as? MutableSet<String>) ?: defValues
            override fun getInt(key: String?, defValue: Int): Int = (allMaps[key] as? Number)?.toInt() ?: defValue
            override fun getLong(key: String?, defValue: Long): Long = (allMaps[key] as? Number)?.toLong() ?: defValue
            override fun getFloat(key: String?, defValue: Float): Float = (allMaps[key] as? Number)?.toFloat() ?: defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = (allMaps[key] as? Boolean) ?: defValue
            override fun contains(key: String?): Boolean = allMaps.containsKey(key)
            override fun edit(): SharedPreferences.Editor = primaryPrefs.edit()
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }
    }

    private fun File.nameWithoutSuffix(suffix: String): String =
        if (name.endsWith(suffix)) name.substring(0, name.length - suffix.length) else name
}
