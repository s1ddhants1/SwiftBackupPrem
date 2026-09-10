package io.github.s1ddhants1.swiftbackupprem.util

import android.annotation.SuppressLint
import android.content.Context
import io.github.s1ddhants1.swiftbackupprem.hook.ResolvedTargets
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Shared cryptography and metadata decoding engine for Facebook Conceal (AES-GCM-256),
 * Zstandard decompression, and UID resolution across Cloud Discovery and Backup Rebuilder.
 */
object BackupCrypto {

    private const val CONCEAL_ENTITY = "SwiftBackup_Entity"

    fun deriveConcealKey(uid: String): ByteArray {
        val repeated = uid.repeat((32 / uid.length) + 1).take(32)
        return repeated.toByteArray(StandardCharsets.UTF_8)
    }

    fun concealDecrypt(base64Payload: String, key: ByteArray): ByteArray {
        val raw = Base64.getDecoder().decode(base64Payload.trim().replace("\n", "").replace("\r", ""))
        val version = raw[0]
        val cipherId = raw[1]
        val iv = raw.copyOfRange(2, 14)
        val cipherTextAndTag = raw.copyOfRange(14, raw.size)

        val aad = byteArrayOf(version, cipherId) + CONCEAL_ENTITY.toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(cipherTextAndTag)
    }

    fun concealDecryptRawBytes(raw: ByteArray, key: ByteArray): ByteArray {
        if (raw.size < 14) return raw
        val version = raw[0]
        val cipherId = raw[1]
        if (version != 1.toByte() || cipherId != 2.toByte()) return raw
        return try {
            val iv = raw.copyOfRange(2, 14)
            val cipherTextAndTag = raw.copyOfRange(14, raw.size)
            val aad = byteArrayOf(version, cipherId) + CONCEAL_ENTITY.toByteArray(StandardCharsets.UTF_8)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(cipherTextAndTag)
        } catch (_: Throwable) {
            raw
        }
    }

    fun concealEncrypt(plaintext: String, key: ByteArray): String {
        val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
        val aad = byteArrayOf(1, 2) + CONCEAL_ENTITY.toByteArray(StandardCharsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val cipherTextAndTag = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

        val output = byteArrayOf(1, 2) + iv + cipherTextAndTag
        return Base64.getEncoder().encodeToString(output)
    }

    fun decompressZstdOrRaw(bytes: ByteArray, classLoader: ClassLoader): String? {
        try {
            val str = String(bytes, StandardCharsets.UTF_8)
            if (str.startsWith("{") && str.endsWith("}")) return str
        } catch (_: Throwable) {}

        return attempt("decompress Zstandard payload", silent = true) {
            val unb64 = Base64.getDecoder().decode(bytes)
            val zstdClass = loadClassFlexible(classLoader, "com.swiftapps.sba.SbaZstdNative") ?: return@attempt null
            val nativeInstance = zstdClass.getDeclaredField("a").apply { isAccessible = true }.get(null)
            val decompressMethod = zstdClass.getDeclaredMethod("decompressZstdBytes", ByteArray::class.java)
            val decompressed = decompressMethod.invoke(nativeInstance, unb64) as? ByteArray
            decompressed?.let { String(it, StandardCharsets.UTF_8) }
        }
    }

    data class DecryptedExtra(
        val ssaid: String? = null,
        val permissionStatesCsv: String? = null,
        val notificationPolicyXml: String? = null,
        val versionCode: Long = 1L,
        val versionName: String = "1.0",
        val resolvedUid: String = ""
    )

    fun parseExtraPayload(
        rawExtraText: String,
        candidateUids: List<String>,
        classLoader: ClassLoader
    ): DecryptedExtra? {
        val parts = rawExtraText.split(":::").filter { it.isNotBlank() }
        if (parts.size < 3) return null

        val payload = parts[2].trim()
        for (candUid in candidateUids) {
            try {
                val key = deriveConcealKey(candUid)
                val decBytes = concealDecrypt(payload, key)
                val decompJson = decompressZstdOrRaw(decBytes, classLoader) ?: continue
                val json = JSONObject(decompJson)
                return DecryptedExtra(
                    ssaid = json.optString("ssaid").takeIf { it.isNotBlank() },
                    permissionStatesCsv = json.optString("permissionStatesCsv").takeIf { it.isNotBlank() },
                    notificationPolicyXml = json.optString("notificationPolicyXml").takeIf { it.isNotBlank() },
                    versionCode = json.optLong("versionCode", 1L),
                    versionName = json.optString("versionName", "1.0"),
                    resolvedUid = candUid
                )
            } catch (_: Throwable) {}
        }
        return null
    }

    data class DecryptedFolderManifest(
        val sourcePath: String = "/storage/emulated/0",
        val displayName: String = "",
        val created: Long = 0L,
        val backupId: String = ""
    )

    fun parseFolderManifest(
        rawFlmText: String,
        candidateUids: List<String>,
        classLoader: ClassLoader
    ): DecryptedFolderManifest? {
        val parts = rawFlmText.split(":::").filter { it.isNotBlank() }
        if (parts.size < 3) return null

        val payload = parts[2].trim()
        for (candUid in candidateUids) {
            try {
                val key = deriveConcealKey(candUid)
                val decBytes = concealDecrypt(payload, key)
                val decompJson = decompressZstdOrRaw(decBytes, classLoader) ?: continue
                val json = JSONObject(decompJson)
                val srcPath = json.optString("sourcePath").takeIf { it.isNotBlank() } ?: "/storage/emulated/0"
                val name = srcPath.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() } ?: srcPath
                val created = json.optLong("created", 0L)
                val bId = json.optString("backupId")
                return DecryptedFolderManifest(
                    sourcePath = srcPath,
                    displayName = name,
                    created = created,
                    backupId = bId
                )
            } catch (_: Throwable) {}
        }
        return null
    }

    @SuppressLint("SdCardPath")
    fun resolveCandidateUids(context: Context?, classLoader: ClassLoader, targets: ResolvedTargets? = null): List<String> {
        val uids = LinkedHashSet<String>()
        uids.add(BackupMigratorEngine.SWIFT_BACKUP_ANONYMOUS_UID)

        // 1. Resolved user classes from targets
        targets?.authUserClass?.let { cls ->
            attempt("resolve UID via authUserClass", silent = true) {
                val user = cls.declaredMethods.firstOrNull { it.parameterCount == 0 && java.lang.reflect.Modifier.isStatic(it.modifiers) }?.invoke(null)
                val uid = user?.javaClass?.getMethod("getUid")?.invoke(user) as? String
                if (!uid.isNullOrBlank()) uids.add(uid)
            }
        }
        targets?.anonUserClass?.let { cls ->
            attempt("resolve UID via anonUserClass", silent = true) {
                val user = cls.declaredMethods.firstOrNull { it.parameterCount == 0 && java.lang.reflect.Modifier.isStatic(it.modifiers) }?.invoke(null)
                val uid = user?.javaClass?.getMethod("getUid")?.invoke(user) as? String
                if (!uid.isNullOrBlank()) uids.add(uid)
            }
        }

        // 2. FirebaseAuth.getInstance().getCurrentUser().getUid()
        attempt("resolve UID via FirebaseAuth", silent = true) {
            val fbAuthClass = classLoader.loadClass("com.google.firebase.auth.FirebaseAuth")
            val authInstance = fbAuthClass.getDeclaredMethod("getInstance").invoke(null)
            val currentUser = authInstance?.let { fbAuthClass.getDeclaredMethod("getCurrentUser").invoke(it) }
            val uid = currentUser?.let { it.javaClass.getDeclaredMethod("getUid").invoke(it) as? String }
            if (!uid.isNullOrBlank()) uids.add(uid)
        }

        val knownAccountHashes = HashSet<String>()
        attempt("find local account folder hashes", silent = true) {
            val accountsDir = File("/storage/emulated/0/SwiftBackup/accounts")
            if (accountsDir.exists() && accountsDir.isDirectory) {
                accountsDir.listFiles()?.filter { it.isDirectory }?.forEach { knownAccountHashes.add(it.name) }
            }
        }

        fun extractUidsFromText(text: String) {
            if (text.isBlank()) return
            // A. Firebase Auth GET_TOKEN_RESPONSE keys: com.google.firebase.auth.GET_TOKEN_RESPONSE.<UID>
            val matcherAuth = Pattern.compile("com\\.google\\.firebase\\.auth\\.GET_TOKEN_RESPONSE\\.([a-zA-Z0-9]{20,36})").matcher(text)
            while (matcherAuth.find()) {
                val uid = matcherAuth.group(1)
                if (!uid.isNullOrBlank()) uids.add(uid)
            }
            // B. JSON UID fields: "uid": "..."
            val matcherJsonUid = Pattern.compile("[\"\\\\]+uid[\"\\\\]+[:=]+[\"\\\\]+([a-zA-Z0-9]{20,36})[\"\\\\]+").matcher(text)
            while (matcherJsonUid.find()) {
                val uid = matcherJsonUid.group(1)
                if (!uid.isNullOrBlank()) uids.add(uid)
            }
            // C. JSON localId fields
            val matcherLocalId = Pattern.compile("[\"\\\\]+localId[\"\\\\]+[:=]+[\"\\\\]+([a-zA-Z0-9]{20,36})[\"\\\\]+").matcher(text)
            while (matcherLocalId.find()) {
                val uid = matcherLocalId.group(1)
                if (!uid.isNullOrBlank()) uids.add(uid)
            }
            // D. Tokens matching known account hashes on disk
            val matcherCandidates = Pattern.compile("(?<=[^a-zA-Z0-9]|^)([a-zA-Z0-9]{28})(?=[^a-zA-Z0-9]|$)").matcher(text)
            while (matcherCandidates.find()) {
                val candidate = matcherCandidates.group(1)
                if (!candidate.isNullOrBlank() && knownAccountHashes.contains(BackupMigratorEngine.computeAccountHash(candidate))) {
                    uids.add(candidate)
                }
            }
        }

        // 3. Direct SharedPreferences file access (if readable)
        attempt("resolve UIDs from shared_prefs Store XMLs and app preferences", silent = true) {
            val candidateDirs = mutableListOf<File>()
            if (context != null) {
                context.filesDir?.parentFile?.let { File(it, "shared_prefs").takeIf { f -> f.exists() }?.let { d -> candidateDirs.add(d) } }
            }
            candidateDirs.add(File("/data/data/org.swiftapps.swiftbackup/shared_prefs"))

            for (sharedPrefsDir in candidateDirs) {
                if (sharedPrefsDir.exists()) {
                    sharedPrefsDir.listFiles()?.forEach { file ->
                        try {
                            extractUidsFromText(file.readText(StandardCharsets.UTF_8))
                        } catch (_: Throwable) {}
                    }
                }
            }
        }

        // 4. Shared storage sync files written by LSPosed hook or backup migrator
        attempt("resolve UIDs from shared storage sync files", silent = true) {
            val syncFiles = listOf(
                File("/storage/emulated/0/SwiftBackup/.sbp_detected_uids"),
                File("/storage/emulated/0/SwiftBackup/.sbp_detected_uid"),
                File("/storage/emulated/0/Android/data/org.swiftapps.swiftbackup/files/.sbp_detected_uids"),
                File("/storage/emulated/0/Android/data/org.swiftapps.swiftbackup/files/.sbp_detected_uid")
            )
            for (file in syncFiles) {
                if (file.exists() && file.canRead()) {
                    extractUidsFromText(file.readText(StandardCharsets.UTF_8))
                }
            }
        }

        // 5. Root Shell execution with multiple su binary fallbacks & timeout
        attempt("resolve UIDs via root shell from Swift Backup", silent = true) {
            val suBins = listOf("su", "/system/bin/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/data/adb/magisk/su")
            for (suBin in suBins) {
                try {
                    val proc = Runtime.getRuntime().exec(arrayOf(suBin, "-c", "cat /data/data/org.swiftapps.swiftbackup/shared_prefs/* 2>/dev/null"))
                    val reader = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
                    val out = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        out.appendLine(line)
                    }
                    proc.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    val result = out.toString()
                    if (result.isNotBlank()) {
                        extractUidsFromText(result)
                        break
                    }
                } catch (_: Throwable) {}
            }
        }

        return uids.toList()
    }

    fun syncDetectedUids(
        context: Context?,
        uids: List<String>,
        targetDirs: List<File>? = null
    ): List<String> {
        if (uids.isEmpty()) return emptyList()
        val allMergedUids = LinkedHashSet<String>()
        attempt("sync detected UIDs to storage", silent = true) {
            val exportDirs = targetDirs ?: listOfNotNull(
                File("/storage/emulated/0/SwiftBackup"),
                File("/storage/emulated/0/Android/data/org.swiftapps.swiftbackup/files"),
                context?.getExternalFilesDir(null)
            )
            for (dir in exportDirs) {
                try {
                    if (!dir.exists()) dir.mkdirs()
                    if (dir.exists() && dir.isDirectory) {
                        val file = File(dir, ".sbp_detected_uids")
                        val existingLines = LinkedHashSet<String>()
                        val fileExisted = file.exists()
                        if (fileExisted && file.canRead()) {
                            file.readLines(StandardCharsets.UTF_8).forEach { line ->
                                val trimmed = line.trim()
                                if (trimmed.isNotEmpty()) {
                                    existingLines.add(trimmed)
                                    allMergedUids.add(trimmed)
                                }
                            }
                        }
                        var hasNew = false
                        for (uid in uids) {
                            val trimmed = uid.trim()
                            if (trimmed.isNotEmpty()) {
                                allMergedUids.add(trimmed)
                                if (existingLines.add(trimmed)) {
                                    hasNew = true
                                }
                            }
                        }
                        if (!fileExisted || hasNew) {
                            file.writeText(existingLines.joinToString("\n") + "\n", StandardCharsets.UTF_8)
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
        return if (allMergedUids.isNotEmpty()) allMergedUids.toList() else uids
    }
}

