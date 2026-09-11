package io.github.s1ddhants1.swiftbackupprem.hook.experimental

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import androidx.annotation.Keep
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.hook.hookTracked
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.hook.HookHandler
import io.github.s1ddhants1.swiftbackupprem.hook.ResolvedTargets
import io.github.s1ddhants1.swiftbackupprem.hook.experimental.cloudproviders.CloudFileItem
import io.github.s1ddhants1.swiftbackupprem.hook.experimental.cloudproviders.CloudScannerRegistry
import io.github.s1ddhants1.swiftbackupprem.hook.getFieldValue
import io.github.s1ddhants1.swiftbackupprem.util.BackupCrypto
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.AppUtils
import io.github.s1ddhants1.swiftbackupprem.util.ApkRangeManifestParser
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import io.github.s1ddhants1.swiftbackupprem.util.loadClassFlexible
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

@Keep
object CloudDiscoveryHook : HookHandler {

    private const val TAG = Consts.TAG
    private const val CACHE_FILE_NAME = "cloud_discovered_cache.json"
    val discoveredBackups = ConcurrentHashMap<String, CopyOnWriteArrayList<DiscoveredCloudApp>>()
    val discoveredFolders = ConcurrentHashMap<String, DiscoveredCloudFolder>()
    val discoveredCalls = ConcurrentHashMap<String, DiscoveredCloudCall>()
    val discoveredSms = ConcurrentHashMap<String, DiscoveredCloudSms>()
    val discoveredWalls = ConcurrentHashMap<String, DiscoveredCloudWall>()
    val discoveredWifi = ConcurrentHashMap<String, DiscoveredCloudWifi>()

    private val isScanRunning = AtomicBoolean(false)
    @Volatile
    private var scanExecutor = createScanExecutor()

    private fun createScanExecutor() = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SBP-CloudDiscovery").apply { isDaemon = true }
    }

    fun shutdown() {
        try { scanExecutor.shutdownNow() } catch (_: Throwable) {}
        scanExecutor = createScanExecutor()
        isScanRunning.set(false)
    }

    data class DiscoveredCloudFolder(
        val id: String,
        val displayName: String,
        val tag: String,
        val fldLink: String? = null,
        val fldSize: Long = 0,
        val flmLink: String? = null,
        val flmSize: Long = 0,
        val totalSize: Long = 0,
        val timestamp: Long = System.currentTimeMillis(),
        val sourceFolder: String = "/storage/emulated/0",
        val provider: String = "Generic"
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("displayName", displayName)
            put("tag", tag)
            fldLink?.let { put("fldLink", it) }
            put("fldSize", fldSize)
            flmLink?.let { put("flmLink", it) }
            put("flmSize", flmSize)
            put("totalSize", totalSize)
            put("timestamp", timestamp)
            put("sourceFolder", sourceFolder)
            put("provider", provider)
        }

        companion object {
            fun fromJson(id: String, obj: JSONObject): DiscoveredCloudFolder = DiscoveredCloudFolder(
                id = obj.optString("id", id),
                displayName = obj.optString("displayName", "Folder-$id"),
                tag = obj.optString("tag", "DEFAULT"),
                fldLink = obj.optString("fldLink").takeIf { it.isNotBlank() },
                fldSize = obj.optLong("fldSize", 0L),
                flmLink = obj.optString("flmLink").takeIf { it.isNotBlank() },
                flmSize = obj.optLong("flmSize", 0L),
                totalSize = obj.optLong("totalSize", 0L),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                sourceFolder = obj.optString("sourceFolder", "/storage/emulated/0"),
                provider = obj.optString("provider", "Generic")
            )
        }
    }

    data class DiscoveredCloudCall(
        val fileId: String,
        val fileName: String,
        val size: Long,
        val count: Int,
        val tag: String,
        val timestamp: Long,
        val provider: String = "Generic"
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("fileId", fileId); put("fileName", fileName); put("size", size)
            put("count", count); put("tag", tag); put("timestamp", timestamp)
            put("provider", provider)
        }
        companion object {
            fun fromJson(obj: JSONObject): DiscoveredCloudCall = DiscoveredCloudCall(
                fileId = obj.optString("fileId", ""),
                fileName = obj.optString("fileName", ""),
                size = obj.optLong("size", 0L),
                count = obj.optInt("count", 1),
                tag = obj.optString("tag", "DEFAULT"),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                provider = obj.optString("provider", "Generic")
            )
        }
    }

    data class DiscoveredCloudSms(
        val fileId: String,
        val fileName: String,
        val size: Long,
        val totalCount: Int,
        val tag: String,
        val timestamp: Long,
        val provider: String = "Generic"
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("fileId", fileId); put("fileName", fileName); put("size", size)
            put("totalCount", totalCount); put("tag", tag); put("timestamp", timestamp)
            put("provider", provider)
        }
        companion object {
            fun fromJson(obj: JSONObject): DiscoveredCloudSms = DiscoveredCloudSms(
                fileId = obj.optString("fileId", ""),
                fileName = obj.optString("fileName", ""),
                size = obj.optLong("size", 0L),
                totalCount = obj.optInt("totalCount", 1),
                tag = obj.optString("tag", "DEFAULT"),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                provider = obj.optString("provider", "Generic")
            )
        }
    }

    data class DiscoveredCloudWall(
        val fileId: String,
        val fileName: String,
        val size: Long,
        val timestamp: Long,
        val thumbnailLink: String? = null,
        val provider: String = "Generic"
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("fileId", fileId); put("fileName", fileName); put("size", size); put("timestamp", timestamp)
            thumbnailLink?.let { put("thumbnailLink", it) }
            put("provider", provider)
        }
        companion object {
            fun fromJson(obj: JSONObject): DiscoveredCloudWall = DiscoveredCloudWall(
                fileId = obj.optString("fileId", ""),
                fileName = obj.optString("fileName", ""),
                size = obj.optLong("size", 0L),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                thumbnailLink = obj.optString("thumbnailLink").takeIf { it.isNotBlank() },
                provider = obj.optString("provider", "Generic")
            )
        }
    }

    data class DiscoveredCloudWifi(
        val fileId: String,
        val fileName: String,
        val size: Long,
        val count: Int,
        val provider: String = "Generic"
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("fileId", fileId); put("fileName", fileName); put("size", size); put("count", count)
            put("provider", provider)
        }
        companion object {
            fun fromJson(obj: JSONObject): DiscoveredCloudWifi = DiscoveredCloudWifi(
                fileId = obj.optString("fileId", ""),
                fileName = obj.optString("fileName", ""),
                size = obj.optLong("size", 0L),
                count = obj.optInt("count", 1),
                provider = obj.optString("provider", "Generic")
            )
        }
    }

    data class DiscoveredCloudApp(
        val packageName: String,
        val sanitizedAppId: String,
        val backupId: String,
        val backupTag: String,
        val appName: String? = null,
        val apkLink: String? = null,
        val apkSize: Long = 0,
        val apkBackupDate: Long = 0L,
        val dataLink: String? = null,
        val dataSize: Long = 0,
        val dataBackupDate: Long = 0L,
        val extDataLink: String? = null,
        val extDataSize: Long = 0,
        val extDataBackupDate: Long = 0L,
        val splitsLink: String? = null,
        val splitsSize: Long = 0,
        val splitsBackupDate: Long = 0L,
        val extraLink: String? = null,
        val extraSize: Long = 0,
        val totalSize: Long = 0,
        val ssaid: String? = null,
        val permissionStatesCsv: String? = null,
        val notificationPolicyXml: String? = null,
        val versionCode: Long = 1L,
        val versionName: String = "1.0",
        val dateBackup: Long = System.currentTimeMillis(),
        val provider: String = "Generic"
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("packageName", packageName)
            put("sanitizedAppId", sanitizedAppId)
            put("backupId", backupId)
            put("backupTag", backupTag)
            appName?.let { put("appName", it) }
            apkLink?.let { put("apkLink", it) }
            put("apkSize", apkSize)
            put("apkBackupDate", apkBackupDate)
            dataLink?.let { put("dataLink", it) }
            put("dataSize", dataSize)
            put("dataBackupDate", dataBackupDate)
            extDataLink?.let { put("extDataLink", it) }
            put("extDataSize", extDataSize)
            put("extDataBackupDate", extDataBackupDate)
            splitsLink?.let { put("splitsLink", it) }
            put("splitsSize", splitsSize)
            put("splitsBackupDate", splitsBackupDate)
            extraLink?.let { put("extraLink", it) }
            put("extraSize", extraSize)
            put("totalSize", totalSize)
            ssaid?.let { put("ssaid", it) }
            permissionStatesCsv?.let { put("permissionStatesCsv", it) }
            notificationPolicyXml?.let { put("notificationPolicyXml", it) }
            put("versionCode", versionCode)
            put("versionName", versionName)
            put("dateBackup", dateBackup)
            put("provider", provider)
        }

        companion object {
            fun fromJson(pkg: String, obj: JSONObject): DiscoveredCloudApp {
                fun s(vararg keys: String): String? {
                    for (k in keys) {
                        val v = obj.optString(k, "").trim()
                        if (v.isNotBlank() && v != "null") return v
                    }
                    return null
                }
                fun l(vararg keys: String): Long {
                    for (k in keys) {
                        if (obj.has(k)) {
                            val v = obj.optLong(k, -1L)
                            if (v != -1L) return v
                        }
                    }
                    return 0L
                }
                val resolvedPkg = s("packageName", "pkgName", "pName") ?: pkg
                val resolvedSanitized = s("sanitizedAppId", "appId") ?: resolvedPkg.replace(".", "")
                val resolvedBackupId = s("backupId", "id") ?: ""
                val resolvedTag = s("backupTag", "tag") ?: "DEFAULT"
                val resolvedAppName = s("appName", "name")
                val resolvedApkLink = s("apkLink")
                val resolvedApkSize = l("apkSize")
                val resolvedApkDate = l("apkBackupDate")
                val resolvedDataLink = s("dataLink")
                val resolvedDataSize = l("dataSize")
                val resolvedDataDate = l("dataBackupDate")
                val resolvedExtDataLink = s("extDataLink")
                val resolvedExtDataSize = l("extDataSize")
                val resolvedExtDataDate = l("extDataBackupDate")
                val resolvedSplitsLink = s("splitsLink")
                val resolvedSplitsSize = l("splitsSize")
                val resolvedSplitsDate = l("splitsBackupDate")
                val resolvedExtraLink = s("extraLink", "specialDataLink")
                val resolvedExtraSize = l("extraSize", "specialDataSize")
                val resolvedTotalSize = l("totalSize")
                val resolvedSsaid = s("ssaid")
                val resolvedPerms = s("permissionStatesCsv")
                val resolvedNotif = s("notificationPolicyXml")
                val resolvedVerCode = l("versionCode").takeIf { it > 0 } ?: 1L
                val resolvedVerName = s("versionName") ?: "1.0"
                val resolvedDateBackup = l("dateBackup", "dateBackupUpdated").takeIf { it > 0 } ?: System.currentTimeMillis()
                val resolvedProvider = s("provider") ?: "Generic"

                return DiscoveredCloudApp(
                    packageName = resolvedPkg,
                    sanitizedAppId = resolvedSanitized,
                    backupId = resolvedBackupId,
                    backupTag = resolvedTag,
                    appName = resolvedAppName,
                    apkLink = resolvedApkLink,
                    apkSize = resolvedApkSize,
                    apkBackupDate = resolvedApkDate,
                    dataLink = resolvedDataLink,
                    dataSize = resolvedDataSize,
                    dataBackupDate = resolvedDataDate,
                    extDataLink = resolvedExtDataLink,
                    extDataSize = resolvedExtDataSize,
                    extDataBackupDate = resolvedExtDataDate,
                    splitsLink = resolvedSplitsLink,
                    splitsSize = resolvedSplitsSize,
                    splitsBackupDate = resolvedSplitsDate,
                    extraLink = resolvedExtraLink,
                    extraSize = resolvedExtraSize,
                    totalSize = resolvedTotalSize,
                    ssaid = resolvedSsaid,
                    permissionStatesCsv = resolvedPerms,
                    notificationPolicyXml = resolvedNotif,
                    versionCode = resolvedVerCode,
                    versionName = resolvedVerName,
                    dateBackup = resolvedDateBackup,
                    provider = resolvedProvider
                )
            }
        }
    }

    /**
     * Synthesizes a Firebase DataSnapshot from a DiscoveredCloudApp's metadata.
     *
     * The snapshot structure mirrors what Swift Backup's official RTDB would
     * store: a root node keyed by backupId, containing all CloudMetadata fields.
     * When passed to the native onDataChange pipeline, Swift Backup's own
     * AppCloudBackups.fromSnapshot() decodes it identically to a real RTDB entry.
     */
    object FirebaseSnapshotSynthesizer {

        private const val SYNTH_TAG = "$TAG-Synth"

        private data class FirebaseClasses(
            val nodeUtilities: Class<*>,
            val indexedNode: Class<*>,
            val node: Class<*>,
            val dataSnapshot: Class<*>
        )

        @Volatile
        private var cachedClasses: FirebaseClasses? = null

        private fun resolveFirebaseClasses(classLoader: ClassLoader): FirebaseClasses? {
            cachedClasses?.let { return it }

            // 1. DataSnapshot: public SDK class or resolved via AppCloudBackups.Companion.fromSnapshot parameter type
            val dataSnapshot = listOfNotNull(
                loadClassFlexible(classLoader, "com.google.firebase.database.DataSnapshot"),
                attempt("resolve DataSnapshot via AppCloudBackups.fromSnapshot", silent = true) {
                    val companionClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups\$a")
                        ?: loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups\$Companion")
                    val fromSnapshotMethod = companionClass?.declaredMethods?.firstOrNull { it.name == "fromSnapshot" }
                    fromSnapshotMethod?.parameterTypes?.firstOrNull()
                }
            ).firstOrNull() ?: run {
                Log.d(SYNTH_TAG, "DataSnapshot class not found")
                return null
            }

            // 2. IndexedNode: 2nd parameter of DataSnapshot constructor (DataSnapshot(DatabaseReference, IndexedNode))
            val dsCtor = dataSnapshot.constructors.firstOrNull { it.parameterCount == 2 }
            val indexedNode = listOfNotNull(
                loadClassFlexible(classLoader, "com.google.firebase.database.snapshot.IndexedNode"),
                dsCtor?.parameterTypes?.getOrNull(1)
            ).firstOrNull() ?: run {
                Log.d(SYNTH_TAG, "IndexedNode class not found")
                return null
            }

            // 3. Node: single parameter of IndexedNode.from(Node) or return type of getNode()
            val node = listOfNotNull(
                loadClassFlexible(classLoader, "com.google.firebase.database.snapshot.Node"),
                indexedNode.declaredMethods.firstOrNull { it.parameterCount == 1 && it.returnType == indexedNode }?.parameterTypes?.firstOrNull(),
                indexedNode.declaredMethods.firstOrNull { it.name == "getNode" }?.returnType,
                indexedNode.declaredFields.firstOrNull { it.type.isInterface }?.type
            ).firstOrNull() ?: run {
                Log.d(SYNTH_TAG, "Node class not found")
                return null
            }

            // 4. NodeUtilities: public SDK class, dynamic reflection, or DEX scanner
            val nodeUtils = listOfNotNull(
                loadClassFlexible(classLoader, "com.google.firebase.database.snapshot.NodeUtilities"),
                loadClassFlexible(classLoader, "com.google.firebase.database.snapshot.NodeUtility"),
                attempt("find NodeUtilities dynamically", silent = true) {
                    val candidates = (indexedNode.declaredClasses + node.declaredClasses).toMutableList()
                    candidates.firstOrNull { cls ->
                        cls.declaredMethods.any { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType == node && it.parameterCount in 1..2 }
                    }
                },
                findNodeUtilsInDex(classLoader, node)
            ).firstOrNull() ?: run {
                Log.d(SYNTH_TAG, "NodeUtilities class not found")
                return null
            }

            val resolved = FirebaseClasses(nodeUtils, indexedNode, node, dataSnapshot)
            cachedClasses = resolved
            return resolved
        }

        private fun findNodeUtilsInDex(classLoader: ClassLoader, nodeClass: Class<*>): Class<*>? =
            attempt("find NodeUtilities via dex scan", silent = true) {
                val nodeTypeDesc = "L${nodeClass.name.replace('.', '/')};"
                val apkPaths = mutableSetOf<String>()

                appContext?.applicationInfo?.sourceDir?.let { apkPaths.add(it) }

                attempt("get APKs from ClassLoader", silent = true) {
                    var cl: ClassLoader? = classLoader
                    while (cl != null) {
                        val pathListField = cl.javaClass.declaredFields.firstOrNull { it.name == "pathList" }
                            ?: cl.javaClass.superclass?.declaredFields?.firstOrNull { it.name == "pathList" }
                        if (pathListField != null) {
                            pathListField.isAccessible = true
                            val pathList = pathListField.get(cl)
                            if (pathList != null) {
                                val dexElementsField = pathList.javaClass.declaredFields.firstOrNull { it.name == "dexElements" }
                                dexElementsField?.isAccessible = true
                                val dexElements = dexElementsField?.get(pathList) as? Array<*>
                                if (dexElements != null) {
                                    for (element in dexElements) {
                                        if (element == null) continue
                                        val fileField = element.javaClass.declaredFields.firstOrNull { it.type == java.io.File::class.java }
                                        fileField?.isAccessible = true
                                        val file = fileField?.get(element) as? java.io.File
                                        if (file != null && file.exists() && file.extension == "apk") {
                                            apkPaths.add(file.absolutePath)
                                        }
                                    }
                                }
                            }
                        }
                        cl = cl.parent
                    }
                }

                for (apkPath in apkPaths) {
                    val className = scanApkForNodeMethod(apkPath, nodeTypeDesc)
                    if (!className.isNullOrBlank()) {
                        val loaded = loadClassFlexible(classLoader, className)
                        if (loaded != null) {
                            Log.d(SYNTH_TAG, "Discovered NodeUtilities class: ${loaded.name}")
                            return@attempt loaded
                        }
                    }
                }
                null
            }

        internal fun scanApkForNodeMethod(apkPath: String, nodeTypeDesc: String): String? =
            attempt("scan APK for Node method", silent = true) {
                val file = java.io.File(apkPath)
                if (!file.exists()) return@attempt null
                java.util.zip.ZipFile(file).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name.endsWith(".dex")) {
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            val foundClass = parseDexForNodeMethod(bytes, nodeTypeDesc)
                            if (foundClass != null) {
                                return@attempt foundClass
                            }
                        }
                    }
                }
                null
            }

        internal fun parseDexForNodeMethod(dex: ByteArray, nodeTypeDesc: String): String? =
            attempt("parse DEX for Node method", silent = true) {
                val buffer = java.nio.ByteBuffer.wrap(dex).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val stringIdsOff = buffer.getInt(0x3C)
                val typeIdsOff = buffer.getInt(0x44)
                val protoIdsOff = buffer.getInt(0x4C)
                val methodIdsSize = buffer.getInt(0x58)
                val methodIdsOff = buffer.getInt(0x5C)

                fun getString(idx: Int): String {
                    val off = buffer.getInt(stringIdsOff + idx * 4)
                    var pos = off
                    while ((dex[pos].toInt() and 0x80) != 0) pos++
                    pos++
                    val start = pos
                    while (dex[pos].toInt() != 0) pos++
                    return String(dex, start, pos - start, java.nio.charset.StandardCharsets.UTF_8)
                }

                fun getType(idx: Int): String {
                    val strIdx = buffer.getInt(typeIdsOff + idx * 4)
                    return getString(strIdx)
                }

                for (i in 0 until methodIdsSize) {
                    val mOff = methodIdsOff + i * 8
                    val classIdx = buffer.getShort(mOff).toInt() and 0xFFFF
                    val protoIdx = buffer.getShort(mOff + 2).toInt() and 0xFFFF
                    val pOff = protoIdsOff + protoIdx * 12
                    val returnTypeIdx = buffer.getInt(pOff + 4)
                    if (getType(returnTypeIdx) == nodeTypeDesc) {
                        val paramsOff = buffer.getInt(pOff + 8)
                        if (paramsOff != 0) {
                            val paramCount = buffer.getInt(paramsOff)
                            if (paramCount in 1..2) {
                                val firstParamTypeIdx = buffer.getShort(paramsOff + 4).toInt() and 0xFFFF
                                if (getType(firstParamTypeIdx) == "Ljava/lang/Object;") {
                                    val rawClass = getType(classIdx)
                                    return@attempt rawClass.trimStart('L').trimEnd(';').replace('/', '.')
                                }
                            }
                        }
                    }
                }
                null
            }

        internal fun buildSingleBackupMap(app: DiscoveredCloudApp): Map<String, Any> {
            val backupMap = mutableMapOf<String, Any>(
                "appId" to app.sanitizedAppId,
                "packageName" to app.packageName,
                "name" to (app.appName ?: app.packageName),
                "versionCode" to app.versionCode,
                "versionName" to app.versionName,
                "dateBackup" to app.dateBackup,
                "dateBackupUpdated" to app.dateBackup,
                "backupTag" to app.backupTag,
                "minSBVersionCodeRequired" to 580L,
                "keyVersion" to 1
            )

            fun addSlice(link: String?, size: Long, backupDate: Long, prefix: String, encrypted: Boolean = false) {
                if (!link.isNullOrBlank()) {
                    val date = if (backupDate > 0) backupDate else app.dateBackup
                    backupMap["${prefix}Link"] = link
                    backupMap["${prefix}Size"] = size
                    backupMap["${prefix}BackupDate"] = date
                    if (encrypted) {
                        backupMap["${prefix}Encrypted"] = true
                        backupMap["is${prefix.replaceFirstChar { it.uppercase() }}Encrypted"] = true
                        backupMap["${prefix}EncryptionMethod"] = "StandardEncryption"
                        backupMap["${prefix}SizeMirrored"] = size
                    }
                    backupMap["${prefix}SBVersionCodeRequired"] = 580L
                    backupMap["${prefix}SBVersionNameRequired"] = "v4.2.3"
                }
            }

            addSlice(app.apkLink, app.apkSize, app.apkBackupDate, "apk")
            addSlice(app.dataLink, app.dataSize, app.dataBackupDate, "data", encrypted = true)
            addSlice(app.extDataLink, app.extDataSize, app.extDataBackupDate, "extData", encrypted = true)

            if (!app.splitsLink.isNullOrBlank()) {
                val splitsDate = if (app.splitsBackupDate > 0) app.splitsBackupDate else app.dateBackup
                backupMap["splitsLink"] = app.splitsLink
                backupMap["splitsSize"] = app.splitsSize
                backupMap["splitsBackupDate"] = splitsDate
                backupMap["splitsSBVersionCodeRequired"] = 580L
                backupMap["splitsSBVersionNameRequired"] = "v4.2.3"
            }

            if (!app.extraLink.isNullOrBlank()) {
                backupMap["specialDataLink"] = app.extraLink
                backupMap["specialDataSize"] = app.extraSize
            }

            app.ssaid?.let { backupMap["ssaid"] = it }
            app.permissionStatesCsv?.let { backupMap["permissionStatesCsv"] = it }
            app.notificationPolicyXml?.let { backupMap["notificationPolicyXml"] = it }

            return backupMap
        }

        internal fun buildMetadataMap(app: DiscoveredCloudApp): Map<String, Any> =
            mapOf(app.backupId to buildSingleBackupMap(app))

        internal fun buildMetadataMap(apps: List<DiscoveredCloudApp>): Map<String, Any> =
            apps.associate { it.backupId to buildSingleBackupMap(it) }

        /**
         * Creates a synthetic DataSnapshot from an arbitrary map hierarchy.
         */
        internal fun createSnapshotFromMap(
            classLoader: ClassLoader,
            queryRef: Any,
            dataMap: Map<String, Any>
        ): Any? = attempt("createSnapshotFromMap", silent = true) {
            val fb = resolveFirebaseClasses(classLoader) ?: return@attempt null

            val nodeFromJson = fb.nodeUtilities.declaredMethods.firstOrNull { m ->
                (m.parameterCount == 1 && m.parameterTypes[0] == Any::class.java && fb.node.isAssignableFrom(m.returnType)) ||
                (m.parameterCount == 2 && m.parameterTypes[0] == Any::class.java && fb.node.isAssignableFrom(m.parameterTypes[1]) && fb.node.isAssignableFrom(m.returnType))
            } ?: attempt("fallback NodeFromJSON", silent = true) { fb.nodeUtilities.getMethod("NodeFromJSON", Any::class.java) }

            val nodeObj = if (nodeFromJson != null) {
                if (nodeFromJson.parameterCount == 2) {
                    nodeFromJson.invoke(null, dataMap, null)
                } else {
                    nodeFromJson.invoke(null, dataMap)
                }
            } else {
                null
            } ?: run {
                Log.w(SYNTH_TAG, "NodeFromJSON returned null")
                return@attempt null
            }

            val indexedFromNode = fb.indexedNode.declaredMethods.firstOrNull { m ->
                m.parameterCount == 1 && fb.node.isAssignableFrom(m.parameterTypes[0]) && fb.indexedNode.isAssignableFrom(m.returnType)
            } ?: attempt("fallback IndexedNode.from", silent = true) { fb.indexedNode.getMethod("from", fb.node) }

            val indexedNodeObj = if (indexedFromNode != null) {
                indexedFromNode.invoke(null, nodeObj)
            } else {
                val defaultIndex = attempt("get default index", silent = true) {
                    fb.indexedNode.declaredFields.firstOrNull { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type != fb.indexedNode }?.apply { isAccessible = true }?.get(null)
                }
                val ctor = fb.indexedNode.constructors.firstOrNull { it.parameterCount == 2 && it.parameterTypes[0].isAssignableFrom(nodeObj.javaClass) }
                ctor?.newInstance(nodeObj, defaultIndex)
            } ?: run { Log.w(SYNTH_TAG, "IndexedNode resolution returned null"); return@attempt null }

            val ctor = fb.dataSnapshot.constructors
                .filter { it.parameterCount == 2 }
                .firstOrNull { c ->
                    val p0 = c.parameterTypes[0]
                    val p1 = c.parameterTypes[1]
                    p0.isAssignableFrom(queryRef.javaClass) && p1.isAssignableFrom(indexedNodeObj.javaClass)
                } ?: fb.dataSnapshot.constructors.firstOrNull { it.parameterCount == 2 }

            if (ctor == null) {
                Log.w(SYNTH_TAG, "No matching DataSnapshot constructor found. Available: ${
                    fb.dataSnapshot.constructors.joinToString { c ->
                        "(${c.parameterTypes.joinToString { it.simpleName }})"
                    }
                }")
                return@attempt null
            }

            val p0 = ctor.parameterTypes[0]
            val resolvedQueryRef = if (p0.isAssignableFrom(queryRef.javaClass)) {
                queryRef
            } else {
                queryRef.javaClass.methods.firstOrNull { it.parameterCount == 0 && p0.isAssignableFrom(it.returnType) }?.invoke(queryRef)
                    ?: queryRef.javaClass.declaredFields.firstOrNull { p0.isAssignableFrom(it.type) }?.apply { isAccessible = true }?.get(queryRef)
                    ?: queryRef
            }

            ctor.newInstance(resolvedQueryRef, indexedNodeObj)
        }

        /**
         * Creates a synthetic DataSnapshot that the native onDataChange pipeline
         * will process identically to a real Firebase RTDB snapshot.
         */
        fun createSyntheticSnapshot(
            classLoader: ClassLoader,
            queryRef: Any,
            apps: List<DiscoveredCloudApp>
        ): Any? = attempt("synthesize DataSnapshot for multi-backup", silent = true) {
            if (apps.isEmpty()) return@attempt null
            val metadataMap = buildMetadataMap(apps)
            createSnapshotFromMap(classLoader, queryRef, metadataMap)
        }

        fun createSyntheticSnapshot(
            classLoader: ClassLoader,
            queryRef: Any,
            app: DiscoveredCloudApp
        ): Any? = createSyntheticSnapshot(classLoader, queryRef, listOf(app))

        /**
         * Merges existing Firebase RTDB DataSnapshot entries with discovered cloud backups.
         * RTDB data is retained for matching backupIds, while missing backupIds are added.
         * Returns null if all discovered backups already exist in the snapshot.
         */
        fun mergeSnapshotData(
            classLoader: ClassLoader,
            queryRef: Any,
            existingSnapshot: Any,
            discoveredApps: List<DiscoveredCloudApp>
        ): Any? = attempt("mergeSnapshotData", silent = true) {
            if (discoveredApps.isEmpty()) return@attempt null

            val mergedMap = mutableMapOf<String, Any>()

            val childrenIter = attempt("get snapshot children", silent = true) {
                existingSnapshot.javaClass.getMethod("getChildren").invoke(existingSnapshot) as? Iterable<*>
            }

            if (childrenIter != null) {
                for (child in childrenIter) {
                    if (child == null) continue
                    val key = child.javaClass.getMethod("getKey").invoke(child) as? String ?: continue
                    val value = child.javaClass.getMethod("getValue").invoke(child) ?: continue
                    mergedMap[key] = value
                }
            }

            if (mergedMap.isEmpty()) {
                val existingVal = attempt("get snapshot value map", silent = true) {
                    existingSnapshot.javaClass.getMethod("getValue").invoke(existingSnapshot)
                }
                if (existingVal is Map<*, *>) {
                    for ((k, v) in existingVal) {
                        if (k != null && v != null) {
                            mergedMap[k.toString()] = v
                        }
                    }
                }
            }

            var addedCount = 0
            for (app in discoveredApps) {
                if (!mergedMap.containsKey(app.backupId)) {
                    mergedMap[app.backupId] = buildSingleBackupMap(app)
                    addedCount++
                }
            }

            if (addedCount == 0) {
                // All discovered backups already exist in the RTDB snapshot
                return@attempt null
            }

            Log.i(SYNTH_TAG, "Merged $addedCount discovered backup(s) into existing RTDB snapshot (${mergedMap.size} total)")
            createSnapshotFromMap(classLoader, queryRef, mergedMap)
        }
    }

    @Volatile
    var appContext: Context? = null

    @Volatile
    private var preferences: PreferencesManager? = null

    override fun apply(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        appContext = context.applicationContext ?: context
        preferences = prefs
        val canDiscover = (prefs.customFirebaseApp || prefs.unlockLocalCloudFeatures) && prefs.enableCloudDiscovery
        if (!canDiscover) {
            Log.d(TAG, "Cloud Discovery is disabled (requires custom Firebase app or local cloud unlock, and Cloud Discovery enabled)")
            return
        }

        Log.d(TAG, "Applying CloudDiscoveryHook (Universal Cloud discovery & full-app cloud metadata indexing)")
        loadDiskCache(context)
        hookAppCloudBackups(module, context, classLoader, targets)
        startCloudScanWithRetry(context, classLoader, targets)
    }

    private fun isCloudDiscoveryEnabled(): Boolean {
        val p = preferences ?: return false
        if (p.unlockLocalCloudFeatures) return true
        return (p.customFirebaseApp || p.unlockLocalCloudFeatures) && p.enableCloudDiscovery
    }

    private fun isSnapshotInjectionEnabled(): Boolean {
        val p = preferences ?: return false
        if (p.unlockLocalCloudFeatures) return true
        return (p.customFirebaseApp || p.unlockLocalCloudFeatures) && p.enableCloudDiscovery && p.enableSnapshotInjection
    }

    @Volatile
    var lastScanTime = 0L
    private const val SCAN_CACHE_TTL_MS = 60_000L

    fun startCloudScanWithRetry(context: Context, classLoader: ClassLoader, targets: ResolvedTargets) {
        if (!isCloudDiscoveryEnabled()) return
        if (!isScanRunning.compareAndSet(false, true)) return
        scanExecutor.execute {
            try {
                for (delay in longArrayOf(0L, 1000L, 3000L)) {
                    try {
                        if (delay > 0L) Thread.sleep(delay)
                        val count = discoverAllCloudBackups(context, classLoader, targets)
                        if (count > 0 || discoveredBackups.isNotEmpty()) {
                            lastScanTime = System.currentTimeMillis()
                            Log.i(TAG, "[CloudDiscovery] Background scan completed with ${discoveredBackups.size} apps")
                            break
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "[CloudDiscovery] Background scan retry failed: ${t.message}")
                    }
                }
            } finally {
                isScanRunning.set(false)
            }
        }
    }

    fun getAllDiscoveredApps(): List<DiscoveredCloudApp> = discoveredBackups.values.flatten()

    fun addDiscoveredBackup(app: DiscoveredCloudApp) {
        val list = discoveredBackups.getOrPut(app.packageName) { CopyOnWriteArrayList() }
        val existingIndex = list.indexOfFirst { it.backupId == app.backupId }
        if (existingIndex >= 0) {
            list[existingIndex] = app
        } else {
            list.add(app)
        }
    }

    fun ensureScan(context: Context, classLoader: ClassLoader, targets: ResolvedTargets, force: Boolean = false) {
        if (appContext == null) {
            appContext = context.applicationContext ?: context
        }
        val isStale = System.currentTimeMillis() - lastScanTime > SCAN_CACHE_TTL_MS
        if (discoveredBackups.isEmpty() || isStale || force) {
            startCloudScanWithRetry(context, classLoader, targets)
        }
    }

    fun findMatchingBackups(key: String): List<DiscoveredCloudApp> {
        discoveredBackups[key]?.let { if (it.isNotEmpty()) return it }
        val matchingPackage = discoveredBackups.keys().toList().firstOrNull {
            it == key || it.replace(".", "") == key
        }
        if (matchingPackage != null) {
            discoveredBackups[matchingPackage]?.let { if (it.isNotEmpty()) return it }
        }
        return discoveredBackups.values.flatten().filter {
            it.sanitizedAppId == key || it.packageName == key || it.sanitizedAppId == key.replace(".", "")
        }
    }

    fun findMatchingBackup(key: String): DiscoveredCloudApp? = findMatchingBackups(key).firstOrNull()

    private fun createBackupsObject(cloudBackups: List<Any>, classLoader: ClassLoader): Any? =
        loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups")
            ?.getConstructor(List::class.java)?.newInstance(cloudBackups)

    private fun createBackupsObject(cloudBackup: Any, classLoader: ClassLoader): Any? =
        createBackupsObject(listOf(cloudBackup), classLoader)

    fun buildAppCloudBackupsObject(apps: List<DiscoveredCloudApp>, classLoader: ClassLoader): Any? =
        attempt("build AppCloudBackups object", silent = true) {
            val appCloudBackupsClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups") ?: return null
            val appBackupsCtor = appCloudBackupsClass.getConstructor(List::class.java)
            val cloudBackups = apps.mapNotNull { buildAppCloudBackup(it, classLoader) }
            appBackupsCtor.newInstance(cloudBackups)
        }

    private fun getCanonicalCacheFile(): File {
        val dir = File(Environment.getExternalStorageDirectory(), "SwiftBackup")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, CACHE_FILE_NAME)
    }

    @SuppressLint("SdCardPath")
    private fun loadDiskCache(context: Context) {
        try {
            val cacheFile = getCanonicalCacheFile()
            // Check canonical file first, fallback to legacy internal app file if migrating
            val legacyFile = File(context.filesDir?.parentFile, CACHE_FILE_NAME)
            val fileToRead = when {
                cacheFile.exists() && cacheFile.canRead() -> cacheFile
                legacyFile.exists() && legacyFile.canRead() -> {
                    // Migrate legacy file to canonical location
                    try {
                        legacyFile.copyTo(cacheFile, overwrite = true)
                        legacyFile.delete()
                    } catch (_: Throwable) {}
                    cacheFile
                }
                else -> null
            }

            if (fileToRead != null && fileToRead.exists()) {
                val root = JSONObject(fileToRead.readText(StandardCharsets.UTF_8))
                
                val appsObj = root.optJSONObject("apps") ?: root
                appsObj.keys().forEach { pkg ->
                    if (AppUtils.isValidPackageName(pkg)) {
                        val appArray = appsObj.optJSONArray(pkg)
                        if (appArray != null) {
                            for (i in 0 until appArray.length()) {
                                appArray.optJSONObject(i)?.let { appJson ->
                                    addDiscoveredBackup(DiscoveredCloudApp.fromJson(pkg, appJson))
                                }
                            }
                        } else {
                            val appJson = appsObj.optJSONObject(pkg)
                            if (appJson != null) {
                                addDiscoveredBackup(DiscoveredCloudApp.fromJson(pkg, appJson))
                            }
                        }
                    }
                }

                root.optJSONObject("folders")?.let { foldersObj ->
                    foldersObj.keys().forEach { fid ->
                        val fJson = foldersObj.optJSONObject(fid)
                        if (fJson != null) {
                            discoveredFolders[fid] = DiscoveredCloudFolder.fromJson(fid, fJson)
                        }
                    }
                }

                root.optJSONObject("calls")?.let { callsObj ->
                    callsObj.keys().forEach { id ->
                        callsObj.optJSONObject(id)?.let { discoveredCalls[id] = DiscoveredCloudCall.fromJson(it) }
                    }
                }

                root.optJSONObject("sms")?.let { smsObj ->
                    smsObj.keys().forEach { id ->
                        smsObj.optJSONObject(id)?.let { discoveredSms[id] = DiscoveredCloudSms.fromJson(it) }
                    }
                }

                root.optJSONObject("walls")?.let { wallsObj ->
                    wallsObj.keys().forEach { id ->
                        wallsObj.optJSONObject(id)?.let { discoveredWalls[id] = DiscoveredCloudWall.fromJson(it) }
                    }
                }

                root.optJSONObject("wifi")?.let { wifiObj ->
                    wifiObj.keys().forEach { id ->
                        wifiObj.optJSONObject(id)?.let { discoveredWifi[id] = DiscoveredCloudWifi.fromJson(it) }
                    }
                }

                val allAppsCount = getAllDiscoveredApps().size
                lastScanTime = fileToRead.lastModified()
                Log.i(TAG, "[CloudDiscovery] Loaded $allAppsCount apps (${discoveredBackups.size} packages), ${discoveredFolders.size} folders, ${discoveredCalls.size} calls, ${discoveredSms.size} sms, ${discoveredWalls.size} walls, ${discoveredWifi.size} wifi from cache: ${fileToRead.absolutePath}")
            }
        } catch (t: Throwable) {
            Log.d(TAG, "[CloudDiscovery] Error loading cache: ${t.message}")
        }
    }

    private fun saveDiskCache(context: Context) {
        try {
            val root = JSONObject()
            val appsObj = JSONObject()
            discoveredBackups.forEach { (pkg, appList) ->
                val arr = JSONArray()
                appList.forEach { app -> arr.put(app.toJson()) }
                appsObj.put(pkg, arr)
            }
            root.put("apps", appsObj)

            val foldersObj = JSONObject()
            discoveredFolders.forEach { (fid, folder) -> foldersObj.put(fid, folder.toJson()) }
            root.put("folders", foldersObj)

            val callsObj = JSONObject()
            discoveredCalls.forEach { (id, call) -> callsObj.put(id, call.toJson()) }
            root.put("calls", callsObj)

            val smsObj = JSONObject()
            discoveredSms.forEach { (id, s) -> smsObj.put(id, s.toJson()) }
            root.put("sms", smsObj)

            val wallsObj = JSONObject()
            discoveredWalls.forEach { (id, w) -> wallsObj.put(id, w.toJson()) }
            root.put("walls", wallsObj)

            val wifiObj = JSONObject()
            discoveredWifi.forEach { (id, w) -> wifiObj.put(id, w.toJson()) }
            root.put("wifi", wifiObj)

            val jsonStr = root.toString(2)
            val cacheFile = getCanonicalCacheFile()
            cacheFile.writeText(jsonStr, StandardCharsets.UTF_8)
            cacheFile.setReadable(true, false)
            Log.d(TAG, "[CloudDiscovery] Saved cloud discovery cache to ${cacheFile.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "[CloudDiscovery] Failed to save cache: ${t.message}")
        }
    }

    private fun hookAppCloudBackups(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ) {
        val appCloudBackupsClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups") ?: return
        val companionClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups\$a") ?: return

        companionClass.declaredMethods.forEach { m ->
            when (m.name) {
                "fromSnapshot" -> attempt("hook fromSnapshot", silent = true) {
                    module.hookTracked(m, idPrefix = "cloud-discovery-app-backups-fromSnapshot").intercept { chain ->
                        val initialResult = chain.proceed()
                        if (!isSnapshotInjectionEnabled()) return@intercept initialResult
                        if (initialResult != null && !isResultEmpty(initialResult)) return@intercept initialResult

                        ensureScan(context, classLoader, targets)
                        val key = extractSnapshotKey(chain.args.firstOrNull())

                        if (key != null) {
                            val matchingList = findMatchingBackups(key)
                            val cloudBackups = matchingList.mapNotNull { buildAppCloudBackup(it, classLoader) }
                            if (cloudBackups.isNotEmpty()) {
                                Log.i(TAG, "[CloudDiscovery] fromSnapshot injected ${cloudBackups.size} cloud backups for key=$key")
                                return@intercept createBackupsObject(cloudBackups, classLoader)
                            }
                        }
                        initialResult
                    }
                }
                "fetchForPackage" -> attempt("hook fetchForPackage", silent = true) {
                    module.hookTracked(m, idPrefix = "cloud-discovery-app-backups-fetchForPackage").intercept { chain ->
                        val initialResult = chain.proceed()
                        if (!isSnapshotInjectionEnabled()) return@intercept initialResult
                        val pkgName = chain.args.firstOrNull() as? String
                        if (pkgName != null && (initialResult == null || isResultEmpty(initialResult))) {
                            ensureScan(context, classLoader, targets)
                            val matchingList = findMatchingBackups(pkgName)
                            val cloudBackups = matchingList.mapNotNull { buildAppCloudBackup(it, classLoader) }
                            if (cloudBackups.isNotEmpty()) {
                                val backupsObj = createBackupsObject(cloudBackups, classLoader)
                                val resultCtor = m.returnType.constructors.firstOrNull { it.parameterCount == 2 && it.parameterTypes[0] == appCloudBackupsClass }
                                    ?: m.returnType.constructors.firstOrNull { it.parameterCount == 2 }
                                resultCtor?.newInstance(backupsObj, null)?.let { return@intercept it }
                            }
                        }
                        initialResult
                    }
                }
            }
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return String.format(Locale.US, "%.2f %s", size, units[unitIndex])
    }

    private fun isResultEmpty(result: Any): Boolean = attempt("check isResultEmpty", silent = true) {
        val cloudBackups = result.javaClass.getDeclaredMethod("getAppCloudBackups").invoke(result) ?: return@attempt true
        val backupsList = attempt("getBackups", silent = true) {
            cloudBackups.javaClass.getDeclaredMethod("getBackups").invoke(cloudBackups) as? List<*>
        } ?: attempt("field a", silent = true) {
            cloudBackups.getFieldValue("a") as? List<*>
        }
        backupsList == null || backupsList.isEmpty()
    } ?: false

    fun extractSnapshotKey(snapshot: Any?): String? {
        if (snapshot == null) return null
        return attempt("extractSnapshotKey", silent = true) {
            (snapshot.javaClass.methods.firstOrNull { it.name == "getKey" && it.parameterCount == 0 }?.invoke(snapshot) as? String)
                ?: (snapshot.getFieldValue("key") as? String)
                ?: run {
                    val ref = snapshot.getFieldValue("query")
                        ?: snapshot.getFieldValue("a")
                        ?: snapshot.getFieldValue("b")
                    ref?.let { r ->
                        (r.javaClass.methods.firstOrNull { it.name == "getKey" && it.parameterCount == 0 }?.invoke(r) as? String)
                            ?: (r.javaClass.methods.firstOrNull { it.name == "e" && it.parameterCount == 0 && it.returnType == String::class.java }?.invoke(r) as? String)
                            ?: (r.getFieldValue("key") as? String)
                    }
                }
        }
    }

    fun buildAppCloudBackup(app: DiscoveredCloudApp, classLoader: ClassLoader): Any? = attempt("build AppCloudBackup", silent = true) {
        val metaClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.CloudMetadata") ?: return null
        val backupClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackup") ?: return null
        val metaCtor = metaClass.constructors.first { it.parameterCount >= 60 }
        val now = app.dateBackup
        val apkDate = if (app.apkBackupDate > 0) app.apkBackupDate else now
        val dataDate = if (app.dataBackupDate > 0) app.dataBackupDate else now
        val extDataDate = if (app.extDataBackupDate > 0) app.extDataBackupDate else now
        val splitsDate = if (app.splitsBackupDate > 0) app.splitsBackupDate else now
        val args = arrayOfNulls<Any>(metaCtor.parameterCount)

        fun set(idx: Int, value: Any?) { if (idx < args.size) args[idx] = value }

        set(0, app.packageName)
        set(1, app.appName ?: app.packageName)
        set(2, now)
        set(3, now)
        set(4, app.versionName)
        set(5, app.versionCode)
        set(6, app.apkLink)
        if (app.apkSize > 0) set(7, app.apkSize)
        if (app.apkLink != null) { set(8, apkDate); set(9, 580L); set(10, "v4.2.3") }
        set(11, app.splitsLink)
        if (app.splitsSize > 0) set(12, app.splitsSize)
        if (app.splitsLink != null) { set(13, splitsDate); set(14, 580L); set(15, "v4.2.3") }
        set(21, app.dataLink)
        if (app.dataSize > 0) set(22, app.dataSize)
        if (app.dataLink != null) { set(23, dataDate); set(24, true); set(25, "StandardEncryption"); set(27, dataDate); set(28, 580L); set(29, "v4.2.3") }
        set(30, app.extDataLink)
        if (app.extDataSize > 0) set(31, app.extDataSize)
        if (app.extDataLink != null) { set(32, extDataDate); set(33, true); set(34, "StandardEncryption"); set(36, extDataDate); set(37, 580L); set(38, "v4.2.3") }
        set(54, 580L)
        set(56, app.permissionStatesCsv)
        set(58, app.extraLink)
        if (app.extraSize > 0) set(59, app.extraSize)
        set(61, app.ssaid)
        set(63, false)
        set(65, 1)

        val metaObj = metaCtor.newInstance(*args)
        backupClass.getConstructor(String::class.java, metaClass).newInstance(app.backupId, metaObj)
    }

    fun discoverAllCloudBackups(
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ): Int {
        val sp: SharedPreferences = attempt("get swiftbackup prefs", silent = true) {
            context.getSharedPreferences("org.swiftapps.swiftbackup_preferences", Context.MODE_PRIVATE)
        } ?: return 0

        val deviceTag = sp.getString("google_drive_cloud_backup_tag", null)
            ?: sp.getString("cloud_backup_tag", null)
            ?: "DEFAULT"

        val candidateUids = resolveCandidateUids(context, classLoader, targets)
        Log.d(TAG, "[CloudDiscovery] Starting discovery across all configured cloud providers...")

        val providerResults = CloudScannerRegistry.scanAllConfiguredProviders(context)
        if (providerResults.isEmpty()) {
            Log.d(TAG, "[CloudDiscovery] No cloud providers returned items")
            return 0
        }

        val appRegex = Pattern.compile("^(.*?)\\.([a-z]+)\\s+\\((.*?)\\)\\s+\\(id-(.*?)\\)$")
        val folderRegex = Pattern.compile("^folder-base\\.(fld|flm)\\s+\\((.*?)\\)\\s+\\(id-(.*?)\\)$")
        val callRegex = Pattern.compile("^v3\\.(\\d+)\\.(\\d+)\\.(.*?)\\.cls(?:\\s+\\((.*?)\\))?$")
        val callFallbackRegex = Pattern.compile("^(.*?)\\.cls(?:\\s+\\((.*?)\\))?$")
        val smsRegex = Pattern.compile("^v3\\.(\\d+)\\.(\\d+)\\.(\\d+)\\.(.*?)\\.msg(?:\\s+\\((.*?)\\))?$")
        val smsFallbackRegex = Pattern.compile("^(.*?)\\.msg(?:\\s+\\((.*?)\\))?$")
        val wallRegex = Pattern.compile("^(.*?)\\.wal(?:\\.png)?(?:\\s+\\((.*?)\\))?$")
        val wifiRegex = Pattern.compile("^(.*?)\\.wfi(?:\\s+\\((.*?)\\))?$")

        var totalIndexedCount = 0

        for (providerResult in providerResults) {
            val scanner = providerResult.scanner
            val fileList = providerResult.items
            val providerName = scanner.providerName

            val appGroups = mutableMapOf<Pair<String, String>, MutableMap<String, Pair<String, CloudFileItem>>>()
            val folderGroups = mutableMapOf<Pair<String, String>, MutableMap<String, CloudFileItem>>()

            for (fileObj in fileList) {
                val fileName = fileObj.name
                val fileSize = fileObj.size
                val fileId = fileObj.id

                val folderMatcher = folderRegex.matcher(fileName)
                if (folderMatcher.matches()) {
                    val part = folderMatcher.group(1) ?: continue
                    val tag = folderMatcher.group(2) ?: deviceTag
                    val fid = folderMatcher.group(3) ?: continue
                    folderGroups.getOrPut(Pair(fid, tag)) { mutableMapOf() }[part] = fileObj
                    continue
                }

                val callMatcher = callRegex.matcher(fileName)
                if (callMatcher.matches()) {
                    val ts = callMatcher.group(1)?.toLongOrNull() ?: fileObj.timestamp
                    val count = callMatcher.group(2)?.toIntOrNull() ?: 1
                    val tag = callMatcher.group(4) ?: deviceTag
                    discoveredCalls[fileId] = DiscoveredCloudCall(fileId, fileName, fileSize, count, tag, ts, providerName)
                    totalIndexedCount++
                    continue
                } else {
                    val callFbMatcher = callFallbackRegex.matcher(fileName)
                    if (callFbMatcher.matches()) {
                        val tag = callFbMatcher.group(1) ?: deviceTag
                        discoveredCalls[fileId] = DiscoveredCloudCall(fileId, fileName, fileSize, 1, tag, fileObj.timestamp, providerName)
                        totalIndexedCount++
                        continue
                    }
                }

                val smsMatcher = smsRegex.matcher(fileName)
                if (smsMatcher.matches()) {
                    val ts = smsMatcher.group(1)?.toLongOrNull() ?: fileObj.timestamp
                    val totalCount = smsMatcher.group(3)?.toIntOrNull() ?: 1
                    val tag = smsMatcher.group(5) ?: deviceTag
                    discoveredSms[fileId] = DiscoveredCloudSms(fileId, fileName, fileSize, totalCount, tag, ts, providerName)
                    totalIndexedCount++
                    continue
                } else {
                    val smsFbMatcher = smsFallbackRegex.matcher(fileName)
                    if (smsFbMatcher.matches()) {
                        val tag = smsFbMatcher.group(1) ?: deviceTag
                        discoveredSms[fileId] = DiscoveredCloudSms(fileId, fileName, fileSize, 1, tag, fileObj.timestamp, providerName)
                        totalIndexedCount++
                        continue
                    }
                }

                val wallMatcher = wallRegex.matcher(fileName)
                if (wallMatcher.matches()) {
                    val rawTs = wallMatcher.group(1)
                    val ts = rawTs?.toLongOrNull() ?: fileObj.timestamp
                    val thumbnailLink = fileObj.thumbnailLink

                    val cleanFileName = when {
                        fileName.contains("home_wall") -> "home_wall.wal"
                        fileName.contains("lock_wall") -> "lock_wall.wal"
                        fileName.endsWith(".wal") && !fileName.contains(" ") -> fileName
                        else -> "$ts.wal"
                    }

                    discoveredWalls[fileId] = DiscoveredCloudWall(
                        fileId = fileId,
                        fileName = cleanFileName,
                        size = fileSize,
                        timestamp = ts,
                        thumbnailLink = thumbnailLink,
                        provider = providerName
                    )
                    totalIndexedCount++
                    continue
                }

                val wifiMatcher = wifiRegex.matcher(fileName)
                if (wifiMatcher.matches()) {
                    discoveredWifi[fileId] = DiscoveredCloudWifi(fileId, fileName, fileSize, 1, providerName)
                    totalIndexedCount++
                    continue
                }

                val appMatcher = appRegex.matcher(fileName)
                if (appMatcher.matches()) {
                    val pkg = appMatcher.group(1) ?: continue
                    if (!AppUtils.isValidPackageName(pkg)) {
                        Log.d(TAG, "[CloudDiscovery] Skipping non-package file: $fileName")
                        continue
                    }
                    val part = appMatcher.group(2) ?: continue
                    val tag = appMatcher.group(3) ?: continue
                    val backupId = appMatcher.group(4) ?: continue
                    appGroups.getOrPut(Pair(pkg, backupId)) { mutableMapOf() }[part] = Pair(tag, fileObj)
                }
            }

            for ((key, partsMap) in appGroups) {
                val (pkg, backupId) = key
                val resolvedTag = partsMap["app"]?.first
                    ?: partsMap["apk"]?.first
                    ?: partsMap["ext"]?.first
                    ?: partsMap["dat"]?.first
                    ?: partsMap.values.firstOrNull { it.first.isNotBlank() && it.first != "DEFAULT" }?.first
                    ?: partsMap.values.firstOrNull()?.first
                    ?: deviceTag

                val parts = partsMap.mapValues { it.value.second }

                // Direct Index Record Loading: if an uploaded index record exists, load it directly without reconstruction
                val metaItem = parts["meta"] ?: parts["json"]
                if (metaItem != null) {
                    val metaText = scanner.downloadFileText(context, sp, metaItem)
                    if (!metaText.isNullOrBlank()) {
                        val parsed = attempt("parse direct cloud index record", silent = true) {
                            val json = JSONObject(metaText)
                            DiscoveredCloudApp.fromJson(pkg, json).copy(provider = providerName)
                        }
                        if (parsed != null && parsed.sanitizedAppId.isNotBlank()) {
                            val apkItem = parts["apk"] ?: parts["app"]
                            val extItem = parts["ext"] ?: parts["extra"]
                            val datItem = parts["dat"] ?: parts["data"]
                            val obbItem = parts["obb"] ?: parts["splits"]
                            val medItem = parts["med"] ?: parts["media"]

                            val finalTag = parsed.backupTag.takeIf { it.isNotBlank() && it != "DEFAULT" } ?: resolvedTag
                            val finalApkLink = parsed.apkLink?.takeIf { it.isNotBlank() } ?: apkItem?.id
                            val finalApkSize = if (parsed.apkSize > 0) parsed.apkSize else (apkItem?.size ?: 0L)
                            val finalExtLink = parsed.extraLink?.takeIf { it.isNotBlank() } ?: extItem?.id
                            val finalExtSize = if (parsed.extraSize > 0) parsed.extraSize else (extItem?.size ?: 0L)
                            val finalDatLink = parsed.dataLink?.takeIf { it.isNotBlank() } ?: datItem?.id
                            val finalDatSize = if (parsed.dataSize > 0) parsed.dataSize else (datItem?.size ?: 0L)
                            val finalSplitsLink = parsed.splitsLink?.takeIf { it.isNotBlank() } ?: obbItem?.id
                            val finalSplitsSize = if (parsed.splitsSize > 0) parsed.splitsSize else (obbItem?.size ?: 0L)

                            val calculatedTotal = if (parsed.totalSize > 0) parsed.totalSize else (
                                finalApkSize + finalExtSize + finalDatSize + finalSplitsSize + (medItem?.size ?: 0L)
                            )

                            val updated = parsed.copy(
                                backupTag = finalTag,
                                apkLink = finalApkLink,
                                apkSize = finalApkSize,
                                extraLink = finalExtLink,
                                extraSize = finalExtSize,
                                dataLink = finalDatLink,
                                dataSize = finalDatSize,
                                splitsLink = finalSplitsLink,
                                splitsSize = finalSplitsSize,
                                totalSize = calculatedTotal
                            )

                            Log.i(TAG, "[CloudDiscovery] Loaded direct cloud index record for $pkg ($backupId) from $providerName without reconstruction")
                            addDiscoveredBackup(updated)
                            totalIndexedCount++
                            continue
                        }
                    }
                }

                val sanitizedAppId = pkg.replace(".", "")
                var versionCode: Long = 0L
                var versionName: String = ""
                var ssaid: String? = null
                var permissionStatesCsv: String? = null
                var notificationPolicyXml: String? = null

                val extraItem = parts["extra"] ?: parts["ext"]
                val extraFileId = extraItem?.id
                val extraSize = extraItem?.size ?: 0L

                val apkItem = parts["apk"] ?: parts["app"]
                val apkFileId = apkItem?.id
                val apkSize = apkItem?.size ?: 0L
                val apkBackupDate = apkItem?.timestamp ?: 0L

                val existingApp = findMatchingBackups(pkg).firstOrNull { it.backupId == backupId }
                var appName = existingApp?.appName?.takeIf { it.isNotBlank() } ?: BackupRebuilderHook.resolveAppLabel(context, pkg)

                if (existingApp != null && existingApp.versionCode > 0) {
                    versionCode = existingApp.versionCode
                    versionName = existingApp.versionName
                    ssaid = existingApp.ssaid
                    permissionStatesCsv = existingApp.permissionStatesCsv
                    notificationPolicyXml = existingApp.notificationPolicyXml
                } else {
                    if (extraItem != null) {
                        val rawExtraText = scanner.downloadFileText(context, sp, extraItem)
                        if (rawExtraText != null) {
                            val extra = BackupCrypto.parseExtraPayload(rawExtraText, candidateUids, classLoader)
                            if (extra != null) {
                                ssaid = extra.ssaid
                                permissionStatesCsv = extra.permissionStatesCsv
                                notificationPolicyXml = extra.notificationPolicyXml
                                if (extra.versionCode > 0) versionCode = extra.versionCode
                                if (extra.versionName.isNotBlank()) versionName = extra.versionName
                            }
                        }
                    }

                    var remoteManifest: ApkRangeManifestParser.ApkManifestInfo? = null
                    if (apkItem != null) {
                        remoteManifest = ApkRangeManifestParser.parseFromScanner(context, sp, scanner, apkItem)
                    }

                    if (remoteManifest != null) {
                        if (remoteManifest.versionCode > 0) versionCode = remoteManifest.versionCode
                        if (remoteManifest.versionName.isNotBlank()) versionName = remoteManifest.versionName
                        if (!remoteManifest.appLabel.isNullOrBlank()) appName = remoteManifest.appLabel
                    }
                }

                val dataItem = parts["dat"] ?: parts["data"]
                val dataFileId = dataItem?.id
                val dataSize = dataItem?.size ?: 0L
                val dataBackupDate = dataItem?.timestamp ?: 0L

                val extDataItem = parts["extdat"] ?: parts["extdata"]
                val extDataFileId = extDataItem?.id
                val extDataSize = extDataItem?.size ?: 0L
                val extDataBackupDate = extDataItem?.timestamp ?: 0L

                val splitsItem = parts["splits"] ?: parts["split"] ?: parts["apks"]
                val splitsFileId = splitsItem?.id
                val splitsSize = splitsItem?.size ?: 0L
                val splitsBackupDate = splitsItem?.timestamp ?: 0L

                val partTimestamps = parts.values.map { it.timestamp }.filter { it > 0L }
                val maxPartTimestamp = partTimestamps.maxOrNull()
                val parsedBackupIdDate = parseBackupIdDate(backupId)
                val calculatedDateBackup = maxPartTimestamp ?: (parsedBackupIdDate ?: System.currentTimeMillis())

                val discovered = DiscoveredCloudApp(
                    packageName = pkg,
                    sanitizedAppId = sanitizedAppId,
                    backupId = backupId,
                    backupTag = resolvedTag,
                    appName = appName,
                    dateBackup = calculatedDateBackup,
                    apkLink = apkFileId,
                    apkSize = apkSize,
                    apkBackupDate = if (apkBackupDate > 0L) apkBackupDate else calculatedDateBackup,
                    dataLink = dataFileId,
                    dataSize = dataSize,
                    dataBackupDate = if (dataBackupDate > 0L) dataBackupDate else calculatedDateBackup,
                    extDataLink = extDataFileId,
                    extDataSize = extDataSize,
                    extDataBackupDate = if (extDataBackupDate > 0L) extDataBackupDate else calculatedDateBackup,
                    splitsLink = splitsFileId,
                    splitsSize = splitsSize,
                    splitsBackupDate = if (splitsBackupDate > 0L) splitsBackupDate else calculatedDateBackup,
                    extraLink = extraFileId,
                    extraSize = extraSize,
                    totalSize = apkSize + dataSize + extDataSize + splitsSize,
                    ssaid = ssaid,
                    permissionStatesCsv = permissionStatesCsv,
                    notificationPolicyXml = notificationPolicyXml,
                    versionCode = if (versionCode > 0L) versionCode else 1L,
                    versionName = versionName.ifBlank { "1.0" },
                    provider = providerName
                )
                addDiscoveredBackup(discovered)
                totalIndexedCount++
            }

            for ((key, parts) in folderGroups) {
                val (fid, tag) = key
                val fldObj = parts["fld"]
                val flmObj = parts["flm"]
                val fldLink = fldObj?.id
                val fldSize = fldObj?.size ?: 0L
                val flmLink = flmObj?.id
                val flmSize = flmObj?.size ?: 0L

                var displayName = "Folder-$fid"
                var sourceFolder = "/storage/emulated/0"
                var backupTimestamp = flmObj?.timestamp ?: fldObj?.timestamp ?: System.currentTimeMillis()

                if (flmObj != null) {
                    val rawFlmText = scanner.downloadFileText(context, sp, flmObj)
                    if (rawFlmText != null) {
                        val manifest = BackupCrypto.parseFolderManifest(rawFlmText, candidateUids, classLoader)
                        if (manifest != null) {
                            sourceFolder = manifest.sourcePath
                            displayName = manifest.displayName
                            if (manifest.created > 0) {
                                backupTimestamp = manifest.created
                            }
                        }
                    }
                }

                if (displayName == "Folder-$fid") {
                    val accountsDir = File(Environment.getExternalStorageDirectory(), "SwiftBackup/accounts")
                    if (accountsDir.isDirectory) {
                        accountsDir.listFiles { f -> f.isDirectory }?.forEach { acc ->
                            val localMetaFile = File(acc, "backups/folders/local/Folder-$fid/metadata.json")
                            if (localMetaFile.exists()) {
                                attempt("read local folder metadata", silent = true) {
                                    val obj = JSONObject(localMetaFile.readText(StandardCharsets.UTF_8))
                                    obj.optJSONObject("folderItem")?.let { item ->
                                        item.optString("displayName").takeIf { it.isNotBlank() }?.let { displayName = it }
                                        item.optString("sourceFolder").takeIf { it.isNotBlank() }?.let { sourceFolder = it }
                                    }
                                }
                            }
                        }
                    }
                }

                val discoveredFolder = DiscoveredCloudFolder(
                    id = fid,
                    displayName = displayName,
                    tag = tag,
                    fldLink = fldLink,
                    fldSize = fldSize,
                    flmLink = flmLink,
                    flmSize = flmSize,
                    totalSize = fldSize + flmSize,
                    timestamp = backupTimestamp,
                    sourceFolder = sourceFolder,
                    provider = providerName
                )
                discoveredFolders[fid] = discoveredFolder
                totalIndexedCount++
            }
        }

        saveDiskCache(context)
        Log.i(TAG, "[CloudDiscovery] Successfully indexed $totalIndexedCount cloud items across providers into catalog")
        return totalIndexedCount
    }

    fun parseBackupIdDate(backupId: String): Long? = attempt("parse backupId timestamp", silent = true) {
        val prefix = backupId.take(15) // "20260826-001043"
        if (prefix.length == 15 && prefix[8] == '-') {
            val sdf = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getDefault()
            return@attempt sdf.parse(prefix)?.time
        }
        null
    }

    fun resolveCandidateUids(context: Context?, classLoader: ClassLoader, targets: ResolvedTargets? = null): List<String> =
        BackupCrypto.resolveCandidateUids(context, classLoader, targets)
}
