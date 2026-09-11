package io.github.s1ddhants1.swiftbackupprem.hook.experimental

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import androidx.core.content.pm.PackageInfoCompat
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.hook.HookHandler
import io.github.s1ddhants1.swiftbackupprem.hook.ResolvedTargets
import io.github.s1ddhants1.swiftbackupprem.util.BackupCrypto
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Hook and engine that automatically detects, decrypts, and reconstructs missing
 * `<packageName>.xml` metadata files for cloud-downloaded and orphaned backups.
 */
@Keep
object BackupRebuilderHook : HookHandler {

    private const val TAG = Consts.TAG

    private fun logI(msg: String) { try { Log.i(TAG, "[BackupRebuilder] $msg") } catch (_: Throwable) {} }

    fun shutdown() {
        // Lifecycle reset on hot reload
    }

    private data class BackupSlice(
        val suffix: String,
        val dateKey: String? = null,
        val sizeKey: String,
        val encryptedKey: String? = null,
        val encryptionMethodKey: String? = null
    )

    override fun apply(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        // No-op: Local backups do not perform background metadata reconstruction via Xposed hooks.
        // Cloud backup metadata reconstruction is bound to Universal Cloud Discovery,
        // and local migration reconstructs metadata by default within BackupMigratorEngine.
    }

    fun resolveAppLabel(context: Context?, pkgName: String, backupDir: File? = null): String {
        if (context != null) {
            attempt("resolve label for installed $pkgName", silent = true) {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(pkgName, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                if (label.isNotBlank()) return label
            }
            if (backupDir != null && backupDir.exists()) {
                val apkFile = File(backupDir, "$pkgName.app").takeIf { it.exists() }
                    ?: File(backupDir, "$pkgName.apk").takeIf { it.exists() }
                if (apkFile != null) {
                    attempt("resolve label from apk $pkgName", silent = true) {
                        val pm = context.packageManager
                        val info = pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
                        val appInfo = info?.applicationInfo
                        if (appInfo != null) {
                            appInfo.sourceDir = apkFile.absolutePath
                            appInfo.publicSourceDir = apkFile.absolutePath
                            val label = pm.getApplicationLabel(appInfo).toString()
                            if (label.isNotBlank()) return label
                        }
                    }
                }
            }
        }
        return pkgName
    }

    @SuppressLint("SetWorldReadable", "SetWorldWritable")
    fun rebuildBackupDirectory(
        backupDir: File,
        pkgName: String,
        backupId: String,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        activeUid: String,
        context: Context? = null
    ): Boolean {
        val xmlFile = File(backupDir, "$pkgName.xml")
        if (xmlFile.exists() && xmlFile.length() > 0) return false

        val slices = listOf(
            BackupSlice("app", "apkBackupDate", "apkBackupSize"),
            BackupSlice("dat", "dataBackupDate", "dataBackupSize", "isDataEncrypted", "dataEncryptionMethod"),
            BackupSlice("extdat", "extDataBackupDate", "extDataBackupSize", "isExtDataEncrypted", "extDataEncryptionMethod"),
            BackupSlice("splits", sizeKey = "splitsBackupSize"),
            BackupSlice("med", "mediaBackupDate", "mediaBackupSize", "isMediaEncrypted", "mediaEncryptionMethod")
        ).map { it to File(backupDir, "$pkgName.${it.suffix}") }
        val extraFile = File(backupDir, "$pkgName.extra")

        if (slices.none { (_, file) -> file.exists() } && !extraFile.exists()) return false

        logI("Found backup slices without .xml at ${backupDir.absolutePath}. Auto-reconstructing metadata...")

        var ssaid: String? = null
        var permissionStatesCsv: String? = null
        var notificationPolicyXml: String? = null
        var resolvedUid = activeUid
        var versionCode = 1L
        var versionName = "1.0"

        if (extraFile.exists() && extraFile.length() > 0) {
            attempt("decrypt .extra file") {
                val candidateUids = BackupCrypto.resolveCandidateUids(context, classLoader, targets)
                val extra = BackupCrypto.parseExtraPayload(extraFile.readText(StandardCharsets.UTF_8), candidateUids, classLoader)
                if (extra != null) {
                    ssaid = extra.ssaid
                    permissionStatesCsv = extra.permissionStatesCsv
                    notificationPolicyXml = extra.notificationPolicyXml
                    versionCode = extra.versionCode
                    versionName = extra.versionName
                    resolvedUid = extra.resolvedUid
                }
            }
        }

        if (context != null) {
            val apkFile = File(backupDir, "$pkgName.app").takeIf { it.exists() }
                ?: File(backupDir, "$pkgName.apk").takeIf { it.exists() }
            if (apkFile != null) {
                attempt("read version info from apk", silent = true) {
                    val info = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                    if (info != null) {
                        versionCode = PackageInfoCompat.getLongVersionCode(info)
                        if (!info.versionName.isNullOrBlank()) versionName = info.versionName!!
                    }
                }
            }
        }

        val appName = resolveAppLabel(context, pkgName, backupDir)
        val now = System.currentTimeMillis()
        val metaJson = JSONObject().apply {
            put("packageName", pkgName)
            put("name", appName)
            put("versionCode", versionCode)
            put("versionName", versionName)
            put("dateBackup", now)
            put("dateBackupUpdated", now)
            put("minSBVersionCodeRequired", 580L)
            put("keyVersion", 1)

            slices.forEach { (slice, file) ->
                if (file.exists()) {
                    slice.dateKey?.let { put(it, now) }
                    put(slice.sizeKey, file.length())
                    if (slice.encryptedKey != null) {
                        put(slice.encryptedKey, true)
                        put(slice.encryptionMethodKey!!, "StandardEncryption")
                        val reqPrefix = when (slice.suffix) {
                            "app" -> "apk"
                            "dat" -> "data"
                            "extdat" -> "extData"
                            "med" -> "media"
                            else -> slice.suffix
                        }
                        put("${reqPrefix}SBVersionCodeRequired", 580L)
                        put("${reqPrefix}SBVersionNameRequired", "v4.2.3")
                    }
                }
            }

            ssaid?.let { put("ssaid", it) }
            permissionStatesCsv?.let { put("permissionStatesCsv", it) }
            notificationPolicyXml?.let { put("notificationPolicyXml", it) }
        }

        val key = deriveConcealKey(resolvedUid)
        val encUid = concealEncrypt(resolvedUid, key)
        val encMeta = concealEncrypt(metaJson.toString(), key)

        xmlFile.writeText("v1:::$encUid:::$encMeta", StandardCharsets.UTF_8)
        xmlFile.setReadable(true, false)
        xmlFile.setWritable(true, false)

        logI("Successfully generated $pkgName.xml for $appName ($pkgName / $backupId)")
        return true
    }

    fun deriveConcealKey(uid: String): ByteArray = BackupCrypto.deriveConcealKey(uid)
    fun concealDecrypt(base64Payload: String, key: ByteArray): ByteArray = BackupCrypto.concealDecrypt(base64Payload, key)
    fun concealEncrypt(plaintext: String, key: ByteArray): String = BackupCrypto.concealEncrypt(plaintext, key)
}
