package io.github.s1ddhants1.swiftbackupprem.util

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * High-performance engine to decrypt, parse, reconstruct missing metadata for,
 * and re-encrypt Swift Backup folder archives for local restore.
 */
object BackupMigratorEngine {

    /**
     * Official static anonymous UID derived from official Swift Backup APK signature:
     * MurmurHash3_128(signature.hashCode()).reversed() = "d58b0944415a4889d7f11aa95fbeca50"
     * Folder hash: MD5(UID)[0..15] = "8690a48a4fcc72f1"
     */
    const val SWIFT_BACKUP_ANONYMOUS_UID = "d58b0944415a4889d7f11aa95fbeca50"

    sealed class TargetEncryptionMode {
        data class Anonymous(val anonymousUid: String = SWIFT_BACKUP_ANONYMOUS_UID) : TargetEncryptionMode()
        data class Custom(val targetUid: String) : TargetEncryptionMode()
        data class Unencrypted(val targetAccountUid: String = SWIFT_BACKUP_ANONYMOUS_UID) : TargetEncryptionMode()
        data object PortableStandard : TargetEncryptionMode()

        val isEncrypted: Boolean
            get() = this is Anonymous || this is Custom

        val isPortable: Boolean
            get() = this is PortableStandard

        val resolvedUid: String
            get() = when (this) {
                is Anonymous -> anonymousUid
                is Custom -> targetUid
                is Unencrypted -> targetAccountUid
                is PortableStandard -> SWIFT_BACKUP_ANONYMOUS_UID
            }
    }

    data class MigrationConfig(
        val sourceDir: File,
        val sourceUid: String,
        val targetMode: TargetEncryptionMode = TargetEncryptionMode.Anonymous(),
        val targetDir: File,
        val syncToFirebase: Boolean = false,
        val firebaseDbUrl: String? = null,
        val firebaseApiKey: String? = null,
        val onProgress: ((MigrationProgress) -> Unit)? = null
    )

    data class MigrationProgress(
        val currentStep: String,
        val processedItems: Int,
        val totalItems: Int,
        val currentFileName: String,
        val logMessage: String? = null
    )

    data class MigrationResult(
        val success: Boolean,
        val totalAppsMigrated: Int,
        val totalFoldersMigrated: Int,
        val totalSyncedToFirebase: Int = 0,
        val targetAccountHash: String?,
        val outputDirectory: File,
        val logs: List<String>,
        val errors: List<String>
    )

    /**
     * Compute the 16-character lowercase MD5 account folder name used by Swift Backup.
     */
    fun computeAccountHash(uid: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(uid.toByteArray(StandardCharsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * Execute full backup migration from source directory to target directory.
     */
    fun migrate(config: MigrationConfig, context: Context? = null): MigrationResult {
        val logs = mutableListOf<String>()
        val errors = mutableListOf<String>()

        fun log(msg: String) {
            logs.add(msg)
            config.onProgress?.invoke(
                MigrationProgress(
                    currentStep = "Migrating",
                    processedItems = 0,
                    totalItems = 0,
                    currentFileName = "",
                    logMessage = msg
                )
            )
        }

        if (!config.sourceDir.exists() || !config.sourceDir.isDirectory) {
            val err = "Source directory does not exist or is not a directory: ${config.sourceDir.absolutePath}"
            errors.add(err)
            return MigrationResult(false, 0, 0, 0, null, config.targetDir, logs, errors)
        }

        val sourceKey = BackupCrypto.deriveConcealKey(config.sourceUid)
        val isEncrypted = config.targetMode.isEncrypted
        val isPortable = config.targetMode.isPortable
        val targetUid = if (isEncrypted) config.targetMode.resolvedUid else null
        val targetKey = targetUid?.let { BackupCrypto.deriveConcealKey(it) }
        val targetAccountHash = if (isPortable) "portable" else computeAccountHash(config.targetMode.resolvedUid)

        val targetBaseDir = if (isPortable) {
            File(config.targetDir, "ExtractedBackups")
        } else {
            File(config.targetDir, "SwiftBackup/accounts/$targetAccountHash/backups")
        }
        val appsTargetBase = if (isPortable) File(targetBaseDir, "apps") else File(targetBaseDir, "apps/local")
        val foldersTargetBase = if (isPortable) File(targetBaseDir, "folders") else File(targetBaseDir, "folders/local")

        appsTargetBase.mkdirs()
        foldersTargetBase.mkdirs()

        log("Starting migration. Mode: ${when {
            isPortable -> "Standard Portable Files (.apk, .tar, .json)"
            isEncrypted -> "Encrypted ($targetUid)"
            else -> "Unencrypted (Swift Backup Plaintext)"
        }}")
        if (!isPortable) log("Target Account Hash: $targetAccountHash")
        log("Source directory: ${config.sourceDir.absolutePath}")
        log("Target directory: ${targetBaseDir.absolutePath}")

        var totalApps = 0
        var totalFolders = 0
        var totalSynced = 0

        // Find candidate app backup folders (any directory containing backup slices or named with timestamp/id)
        val appBackupDirs = findAppBackupDirs(config.sourceDir)
        val folderBackupDirs = findFolderBackupDirs(config.sourceDir)
        val totalWork = appBackupDirs.size + folderBackupDirs.size

        var completedWork = 0

        val authCreds = if (config.syncToFirebase && !config.firebaseDbUrl.isNullOrBlank()) {
            FirebaseSyncEngine.resolveAuthCredentials(context, PreferencesManager(null).apply {
                googleApiKey = config.firebaseApiKey ?: ""
                firebaseDatabaseUrl = config.firebaseDbUrl
            })
        } else null
        val idToken = authCreds?.idToken

        // 1. Process App Backups
        for ((pkgName, backupDir) in appBackupDirs) {
            val backupId = backupDir.name
            val destBackupDir = if (isPortable) File(appsTargetBase, pkgName) else File(appsTargetBase, "$pkgName/$backupId")
            destBackupDir.mkdirs()

            config.onProgress?.invoke(
                MigrationProgress(
                    currentStep = "Processing Apps",
                    processedItems = completedWork,
                    totalItems = totalWork,
                    currentFileName = "$pkgName ($backupId)",
                    logMessage = "Processing app $pkgName..."
                )
            )

            val migrated = processAppBackup(
                backupDir = backupDir,
                destBackupDir = destBackupDir,
                pkgName = pkgName,
                backupId = backupId,
                sourceUid = config.sourceUid,
                sourceKey = sourceKey,
                targetUid = targetUid,
                targetKey = targetKey,
                isPortable = isPortable,
                syncToFirebase = config.syncToFirebase,
                firebaseDbUrl = config.firebaseDbUrl,
                idToken = idToken,
                onSynced = { totalSynced++ },
                context = context,
                log = ::log,
                error = { errors.add(it) }
            )

            if (migrated) totalApps++
            completedWork++
        }

        // 2. Process Folder Backups
        for (folderDir in folderBackupDirs) {
            val folderName = folderDir.name
            val destFolderDir = File(foldersTargetBase, folderName)
            destFolderDir.mkdirs()

            config.onProgress?.invoke(
                MigrationProgress(
                    currentStep = "Processing Folders",
                    processedItems = completedWork,
                    totalItems = totalWork,
                    currentFileName = folderName,
                    logMessage = "Processing folder $folderName..."
                )
            )

            val migrated = processFolderBackup(
                folderDir = folderDir,
                destFolderDir = destFolderDir,
                folderName = folderName,
                sourceUid = config.sourceUid,
                sourceKey = sourceKey,
                targetUid = targetUid,
                targetKey = targetKey,
                isPortable = isPortable,
                syncToFirebase = config.syncToFirebase,
                firebaseDbUrl = config.firebaseDbUrl,
                idToken = idToken,
                onSynced = { totalSynced++ },
                log = ::log,
                error = { errors.add(it) }
            )

            if (migrated) totalFolders++
            completedWork++
        }

        log("Migration finished! Successfully processed $totalApps apps and $totalFolders folders.")

        return MigrationResult(
            success = errors.isEmpty() || (totalApps + totalFolders > 0),
            totalAppsMigrated = totalApps,
            totalFoldersMigrated = totalFolders,
            totalSyncedToFirebase = totalSynced,
            targetAccountHash = targetAccountHash,
            outputDirectory = targetBaseDir,
            logs = logs,
            errors = errors
        )
    }

    /**
     * Locate app backup directories within arbitrary source folder structure.
     */
    fun findAppBackupDirs(sourceDir: File): List<Pair<String, File>> {
        val results = mutableListOf<Pair<String, File>>()

        fun isBackupDir(dir: File): Boolean {
            val files = dir.listFiles() ?: return false
            return files.any { f ->
                f.name.endsWith(".app") || f.name.endsWith(".apk") || f.name.endsWith(".dat") ||
                        f.name.endsWith(".extdat") || f.name.endsWith(".med") || f.name.endsWith(".xml") ||
                        f.name.endsWith(".extra") || f.name.endsWith(".splits")
            }
        }

        sourceDir.walkTopDown().maxDepth(20).filter { it.isDirectory }.forEach { dir ->
            if (isBackupDir(dir)) {
                val parentPkg = dir.parentFile?.name ?: ""
                val pkgName = if (AppUtils.isValidPackageName(parentPkg)) {
                    parentPkg
                } else if (AppUtils.isValidPackageName(dir.name)) {
                    dir.name
                } else {
                    val slice = dir.listFiles()?.firstOrNull { f ->
                        f.name.endsWith(".app") || f.name.endsWith(".apk") || f.name.endsWith(".dat") ||
                                f.name.endsWith(".xml") || f.name.endsWith(".extra") || f.name.endsWith(".splits")
                    }
                    val cand = slice?.name?.substringBeforeLast('.') ?: dir.name
                    if (AppUtils.isValidPackageName(cand)) cand else (if (cand.contains('.')) cand else dir.name)
                }
                if (pkgName.isNotBlank()) {
                    results.add(pkgName to dir)
                }
            }
        }

        return results.distinctBy { it.second.absolutePath }
    }

    /**
     * Locate folder backup directories (named Folder-* or containing folder-base.*).
     */
    fun findFolderBackupDirs(sourceDir: File): List<File> {
        val results = mutableListOf<File>()

        sourceDir.walkTopDown().maxDepth(20).filter { it.isDirectory }.forEach { dir ->
            val hasFolderSlices = dir.listFiles()?.any {
                it.name.startsWith("folder-base.") || it.name == "metadata.json" || it.name.endsWith(".fld") || it.name.endsWith(".flm")
            } == true
            if (dir.name.startsWith("Folder-") || hasFolderSlices) {
                results.add(dir)
            }
        }

        return results.distinctBy { it.absolutePath }
    }

    private fun processAppBackup(
        backupDir: File,
        destBackupDir: File,
        pkgName: String,
        backupId: String,
        sourceUid: String,
        sourceKey: ByteArray,
        targetUid: String?,
        targetKey: ByteArray?,
        isPortable: Boolean = false,
        syncToFirebase: Boolean = false,
        firebaseDbUrl: String? = null,
        idToken: String? = null,
        onSynced: (() -> Unit)? = null,
        context: Context?,
        log: (String) -> Unit,
        error: (String) -> Unit
    ): Boolean {
        try {
            val files = backupDir.listFiles() ?: return false
            var appName = pkgName
            var versionCode = 1L
            var versionName = "1.0"
            var ssaid: String? = null
            var permissionStatesCsv: String? = null
            var notificationPolicyXml: String? = null
            var existingMetaJson: JSONObject? = null

            // 1. Inspect APK if present
            val apkFile = files.firstOrNull { it.name.endsWith(".app") || it.name.endsWith(".apk") }
            if (apkFile != null && context != null) {
                attempt("read apk metadata", silent = true) {
                    val info = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                    if (info != null) {
                        versionCode = PackageInfoCompat.getLongVersionCode(info)
                        if (!info.versionName.isNullOrBlank()) versionName = info.versionName!!
                        val appInfo = info.applicationInfo
                        if (appInfo != null) {
                            appInfo.sourceDir = apkFile.absolutePath
                            appInfo.publicSourceDir = apkFile.absolutePath
                            appName = context.packageManager.getApplicationLabel(appInfo).toString().ifBlank { pkgName }
                        }
                    }
                }
            }

            val classLoader = BackupMigratorEngine::class.java.classLoader ?: ClassLoader.getSystemClassLoader()

            // 2. Decrypt & parse .extra if present
            val extraFile = files.firstOrNull { it.name.endsWith(".extra") }
            if (extraFile != null && extraFile.length() > 0) {
                attempt("decrypt .extra payload", silent = true) {
                    val extraText = extraFile.readText(StandardCharsets.UTF_8)
                    val parts = extraText.split(":::").filter { it.isNotBlank() }
                    if (parts.size >= 3) {
                        val decBytes = BackupCrypto.concealDecrypt(parts[2], sourceKey)
                        val decompJson = BackupCrypto.decompressZstdOrRaw(decBytes, classLoader)
                        if (decompJson != null) {
                            val j = JSONObject(decompJson)
                            ssaid = j.optString("ssaid").takeIf { it.isNotBlank() }
                            permissionStatesCsv = j.optString("permissionStatesCsv").takeIf { it.isNotBlank() }
                            notificationPolicyXml = j.optString("notificationPolicyXml").takeIf { it.isNotBlank() }
                            if (j.has("versionCode")) versionCode = j.optLong("versionCode", versionCode)
                            if (j.has("versionName")) versionName = j.optString("versionName", versionName)
                        }

                        // Write re-encrypted or decrypted .extra to destination
                        val destExtra = if (isPortable) File(destBackupDir, "${pkgName}_extras.json") else File(destBackupDir, "$pkgName.extra")
                        if (targetUid != null && targetKey != null) {
                            val encUid = BackupCrypto.concealEncrypt(targetUid, targetKey)
                            val encPayload = BackupCrypto.concealEncrypt(Base64Wrapper.encodeToString(decBytes), targetKey)
                            destExtra.writeText("v1:::$encUid:::$encPayload", StandardCharsets.UTF_8)
                        } else {
                            destExtra.writeText(decompJson ?: String(decBytes, StandardCharsets.UTF_8), StandardCharsets.UTF_8)
                        }
                        destExtra.setReadable(true, false)
                        destExtra.setWritable(true, false)
                    }
                }
            }

            // 3. Decrypt & parse .xml metadata if present
            val xmlFile = files.firstOrNull { it.name.endsWith(".xml") }
            if (xmlFile != null && xmlFile.length() > 0) {
                attempt("decrypt .xml metadata", silent = true) {
                    val xmlText = xmlFile.readText(StandardCharsets.UTF_8)
                    val parts = xmlText.split(":::").filter { it.isNotBlank() }
                    if (parts.size >= 3) {
                        val decBytes = BackupCrypto.concealDecrypt(parts[2], sourceKey)
                        val decStr = String(decBytes, StandardCharsets.UTF_8)
                        existingMetaJson = JSONObject(decStr)
                        existingMetaJson.let { meta ->
                            if (meta.has("name")) appName = meta.optString("name", appName)
                            if (meta.has("versionCode")) versionCode = meta.optLong("versionCode", versionCode)
                            if (meta.has("versionName")) versionName = meta.optString("versionName", versionName)
                        }
                    }
                }
            }

            // 4. Copy & decrypt data slices (.app, .apk, .splits, .dat, .extdat, .med)
            files.forEach { file ->
                if (!file.name.endsWith(".xml") && !file.name.endsWith(".extra")) {
                    val destFileName = if (isPortable) resolvePortableFileName(pkgName, file.name) else file.name
                    val destFile = File(destBackupDir, destFileName)
                    if (targetUid == null && (file.name.endsWith(".dat") || file.name.endsWith(".extdat") || file.name.endsWith(".med"))) {
                        try {
                            val rawBytes = file.readBytes()
                            val decBytes = BackupCrypto.concealDecryptRawBytes(rawBytes, sourceKey)
                            destFile.writeBytes(decBytes)
                        } catch (_: Throwable) {
                            file.copyTo(destFile, overwrite = true)
                        }
                    } else {
                        file.copyTo(destFile, overwrite = true)
                    }
                    destFile.setReadable(true, false)
                    destFile.setWritable(true, false)
                }
            }

            // 5. Build full reconstructed / updated metadata JSON
            val now = System.currentTimeMillis()
            val metaJson = (existingMetaJson ?: JSONObject()).apply {
                put("packageName", pkgName)
                put("name", appName)
                put("versionCode", versionCode)
                put("versionName", versionName)
                if (!has("dateBackup")) put("dateBackup", now)
                put("dateBackupUpdated", now)
                put("minSBVersionCodeRequired", 580L)
                put("keyVersion", 1)

                val sliceDefinitions = listOf(
                    Triple("app", "apkBackupDate", "apkBackupSize"),
                    Triple("dat", "dataBackupDate", "dataBackupSize"),
                    Triple("extdat", "extDataBackupDate", "extDataBackupSize"),
                    Triple("med", "mediaBackupDate", "mediaBackupSize")
                )

                sliceDefinitions.forEach { (suffix, dateKey, sizeKey) ->
                    val checkFileName = if (isPortable) resolvePortableFileName(pkgName, "$pkgName.$suffix") else "$pkgName.$suffix"
                    val sliceFile = File(destBackupDir, checkFileName)
                    if (sliceFile.exists()) {
                        if (!has(dateKey)) put(dateKey, now)
                        put(sizeKey, sliceFile.length())
                        if (suffix != "app") {
                            val prefix = if (suffix == "dat") "data" else if (suffix == "extdat") "extData" else "media"
                            val capPrefix = prefix.replaceFirstChar { it.uppercase() }
                            if (targetUid != null) {
                                put("is${capPrefix}Encrypted", true)
                                put("${prefix}EncryptionMethod", "StandardEncryption")
                                put("${prefix}SBVersionCodeRequired", 580L)
                                put("${prefix}SBVersionNameRequired", "v4.2.3")
                            } else {
                                put("is${capPrefix}Encrypted", false)
                                remove("${prefix}EncryptionMethod")
                            }
                        }
                    }
                }

                val splitsFileName = if (isPortable) resolvePortableFileName(pkgName, "$pkgName.splits") else "$pkgName.splits"
                val splitsFile = File(destBackupDir, splitsFileName)
                if (splitsFile.exists()) {
                    put("splitsBackupSize", splitsFile.length())
                }

                ssaid?.let { put("ssaid", it) }
                permissionStatesCsv?.let { put("permissionStatesCsv", it) }
                notificationPolicyXml?.let { put("notificationPolicyXml", it) }
            }

            // 6. Write destination metadata file
            val destXml = if (isPortable) File(destBackupDir, "${pkgName}_metadata.json") else File(destBackupDir, "$pkgName.xml")
            if (targetUid != null && targetKey != null) {
                val encUid = BackupCrypto.concealEncrypt(targetUid, targetKey)
                val encMeta = BackupCrypto.concealEncrypt(metaJson.toString(), targetKey)
                destXml.writeText("v1:::$encUid:::$encMeta", StandardCharsets.UTF_8)
            } else {
                destXml.writeText(metaJson.toString(2), StandardCharsets.UTF_8)
            }
            destXml.setReadable(true, false)
            destXml.setWritable(true, false)

            if (syncToFirebase && !firebaseDbUrl.isNullOrBlank()) {
                val syncUid = targetUid ?: sourceUid
                val ok = FirebaseSyncEngine.syncAppMetadata(
                    firebaseDbUrl = firebaseDbUrl,
                    uid = syncUid,
                    pkgName = pkgName,
                    backupId = backupId,
                    metadataJson = metaJson,
                    idToken = idToken
                )
                if (ok) {
                    onSynced?.invoke()
                    log("Synced metadata to Custom Firebase: $pkgName")
                }
            }

            log("Migrated app: $pkgName ($backupId)")
            return true
        } catch (t: Throwable) {
            val err = "Failed to process app $pkgName: ${t.message}"
            error(err)
            return false
        }
    }

    private fun processFolderBackup(
        folderDir: File,
        destFolderDir: File,
        folderName: String,
        sourceUid: String,
        sourceKey: ByteArray,
        targetUid: String?,
        targetKey: ByteArray?,
        isPortable: Boolean = false,
        syncToFirebase: Boolean = false,
        firebaseDbUrl: String? = null,
        idToken: String? = null,
        onSynced: (() -> Unit)? = null,
        log: (String) -> Unit,
        error: (String) -> Unit
    ): Boolean {
        try {
            val files = folderDir.listFiles() ?: return false
            val cleanId = folderName.removePrefix("Folder-").ifBlank { "custom_folder" }
            var sourcePath = "/storage/emulated/0"
            var displayName = folderName
            var created = System.currentTimeMillis()
            val classLoader = BackupMigratorEngine::class.java.classLoader ?: ClassLoader.getSystemClassLoader()

            // 1. Decrypt folder manifest if present
            val flmFile = files.firstOrNull { it.name.endsWith(".flm") }
            if (flmFile != null && flmFile.length() > 0) {
                attempt("decrypt .flm manifest", silent = true) {
                    val rawFlmText = flmFile.readText(StandardCharsets.UTF_8)
                    val parts = rawFlmText.split(":::").filter { it.isNotBlank() }
                    if (parts.size >= 3) {
                        val decBytes = BackupCrypto.concealDecrypt(parts[2], sourceKey)
                        val decompJson = BackupCrypto.decompressZstdOrRaw(decBytes, classLoader)
                        if (decompJson != null) {
                            val j = JSONObject(decompJson)
                            sourcePath = j.optString("sourcePath", sourcePath)
                            displayName = j.optString("displayName", displayName)
                            created = j.optLong("created", created)
                        }

                        // Re-encrypt or write decrypted .flm
                        val destFlm = if (isPortable) File(destFolderDir, "${folderName}_manifest.json") else File(destFolderDir, "folder-base.flm")
                        if (targetUid != null && targetKey != null) {
                            val encUid = BackupCrypto.concealEncrypt(targetUid, targetKey)
                            val encPayload = BackupCrypto.concealEncrypt(Base64Wrapper.encodeToString(decBytes), targetKey)
                            destFlm.writeText("v1:::$encUid:::$encPayload", StandardCharsets.UTF_8)
                        } else {
                            destFlm.writeText(decompJson ?: String(decBytes, StandardCharsets.UTF_8), StandardCharsets.UTF_8)
                        }
                        destFlm.setReadable(true, false)
                        destFlm.setWritable(true, false)
                    }
                }
            }

            // 2. Copy folder archive (.fld) and other assets
            files.forEach { file ->
                if (!file.name.endsWith(".flm") && file.name != "metadata.json") {
                    val destFileName = if (isPortable && file.name == "folder-base.fld") "$folderName.tar" else file.name
                    val destFile = File(destFolderDir, destFileName)
                    if (targetUid == null && file.name == "folder-base.fld") {
                        try {
                            val rawBytes = file.readBytes()
                            val decBytes = BackupCrypto.concealDecryptRawBytes(rawBytes, sourceKey)
                            destFile.writeBytes(decBytes)
                        } catch (_: Throwable) {
                            file.copyTo(destFile, overwrite = true)
                        }
                    } else {
                        file.copyTo(destFile, overwrite = true)
                    }
                    destFile.setReadable(true, false)
                    destFile.setWritable(true, false)
                }
            }

            // 3. Write standard metadata.json
            val destFld = File(destFolderDir, "folder-base.fld")
            val destFlm = File(destFolderDir, "folder-base.flm")
            val fldSize = if (destFld.exists()) destFld.length() else 0L
            val flmSize = if (destFlm.exists()) destFlm.length() else 0L
            val tsFormat = java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.US)
            val tsStr = tsFormat.format(java.util.Date(created))

            val metaJson = JSONObject().apply {
                put("folderItem", JSONObject().apply {
                    put("id", cleanId)
                    put("displayName", displayName)
                    put("sourceFolder", sourcePath)
                    put("setupCreationTime", created)
                })
                put("baseBackup", JSONObject().apply {
                    put("backupLink", destFld.absolutePath)
                    put("backupSize", fldSize)
                    put("manifestLink", destFlm.absolutePath)
                    put("manifestSize", flmSize)
                    put("originalSize", fldSize)
                    put("timestamp", tsStr)
                })
            }

            val metaFile = File(destFolderDir, "metadata.json")
            metaFile.writeText(metaJson.toString(2), StandardCharsets.UTF_8)
            metaFile.setReadable(true, false)
            metaFile.setWritable(true, false)

            if (syncToFirebase && !firebaseDbUrl.isNullOrBlank()) {
                val syncUid = targetUid ?: sourceUid
                val ok = FirebaseSyncEngine.syncFolderMetadata(
                    firebaseDbUrl = firebaseDbUrl,
                    uid = syncUid,
                    folderId = cleanId,
                    metadataJson = metaJson,
                    idToken = idToken
                )
                if (ok) {
                    onSynced?.invoke()
                    log("Synced folder metadata to Custom Firebase: $cleanId")
                }
            }

            log("Migrated folder: $folderName")
            return true
        } catch (t: Throwable) {
            val err = "Failed to process folder $folderName: ${t.message}"
            error(err)
            return false
        }
    }

    private fun resolvePortableFileName(pkgName: String, fileName: String): String = when {
        fileName.endsWith(".app") || fileName.endsWith(".apk") -> "$pkgName.apk"
        fileName.endsWith(".splits") -> "${pkgName}_splits.tar"
        fileName.endsWith(".dat") -> "${pkgName}_data.tar"
        fileName.endsWith(".extdat") -> "${pkgName}_external_data.tar"
        fileName.endsWith(".med") -> "${pkgName}_media.tar"
        fileName.endsWith(".cls") -> "${pkgName}_call_logs.json"
        fileName.endsWith(".msg") -> "${pkgName}_sms_messages.json"
        fileName.endsWith(".wfi") -> "${pkgName}_wifi.json"
        fileName.endsWith(".wal") -> "${pkgName}_wallpaper.png"
        else -> fileName
    }
}

/**
 * Utility wrapper for Base64 encoding across JVM and Android unit tests.
 */
object Base64Wrapper {
    fun encodeToString(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
    fun decode(base64: String): ByteArray = java.util.Base64.getDecoder().decode(base64.trim().replace("\n", "").replace("\r", ""))
}
