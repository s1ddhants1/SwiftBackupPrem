package io.github.s1ddhants1.swiftbackupprem.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.Keep
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.hook.experimental.BackupRebuilderHook
import io.github.s1ddhants1.swiftbackupprem.hook.experimental.CloudDiscoveryHook
import io.github.s1ddhants1.swiftbackupprem.util.AppUtils
import io.github.s1ddhants1.swiftbackupprem.util.FirebaseSyncEngine
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import io.github.s1ddhants1.swiftbackupprem.util.loadClassFlexible
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.util.concurrent.Executors
import java.util.regex.Pattern

/**
 * Clean hook handler for local/anonymous accounts:
 * 1. Hooks public FirebaseUser.isAnonymous() / MFirebaseUser.isAnonymous() to return false, unlocking cloud menus.
 * 2. Hooks AppCloudBackups.Companion.fetchForPackage to directly serve discovered cloud backups without RTDB network errors.
 * 3. Hooks FireSynchronizer dynamically if resolved via DexKit string inspection without hardcoded obfuscated classes.
 * 4. Hooks DatabaseReference write methods to prevent unauthorized RTDB writes and dispatch metadata to cloud storage.
 * 5. Hooks Query read/listener methods to serve synthetic DataSnapshots to ValueEventListener.onDataChange().
 */
@Keep
object LocalCloudUnlockHook : HookHandler {

    fun shutdown() {}


    private const val TAG = Consts.TAG
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val bgExecutor by lazy { Executors.newSingleThreadExecutor { Thread(it, "SBP-LocalCloudSync").apply { isDaemon = true } } }

    override fun apply(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        Log.d(TAG, "[LocalCloudUnlock] Applying LocalCloudUnlockHook (unlockLocalCloudFeatures=${prefs.unlockLocalCloudFeatures})")
        hookIsAnonymous(module, classLoader, targets, prefs)
        hookAppCloudBackups(module, context, classLoader, targets, prefs)
        hookFireSynchronizer(module, context, classLoader, targets, prefs)
        hookDatabaseReferenceWrites(module, context, classLoader, targets, prefs)
        hookQueryListeners(module, context, classLoader, targets, prefs)
    }

    fun hookIsAnonymous(
        module: XposedModule,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        // 1. Standard FirebaseUser public SDK class or Swift Backup's MFirebaseUser
        val fbUserClasses = listOfNotNull(
            loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.anonymous.MFirebaseUser"),
            loadClassFlexible(classLoader, "com.google.firebase.auth.FirebaseUser")
        )
        for (userCls in fbUserClasses) {
            attempt("hook ${userCls.simpleName}.isAnonymous") {
                val m = userCls.methods.firstOrNull { it.name == "isAnonymous" && it.parameterCount == 0 }
                    ?: userCls.declaredMethods.firstOrNull { it.name == "isAnonymous" && it.parameterCount == 0 }
                if (m != null) {
                    module.hookTracked(m, idPrefix = "local-cloud-${userCls.simpleName}-is-anonymous").intercept { chain ->
                        if (prefs.unlockLocalCloudFeatures) {
                            Log.d(TAG, "[LocalCloudUnlock] Intercepted ${userCls.simpleName}.isAnonymous -> false")
                            return@intercept false
                        }
                        chain.proceed()
                    }
                    Log.i(TAG, "[LocalCloudUnlock] Hooked ${userCls.name}.isAnonymous")
                }
            }
        }

        // 2. Custom user classes resolved by DexKit / TargetClassResolver
        for (userClass in listOfNotNull(targets.authUserClass, targets.anonUserClass)) {
            attempt("hook isAnonymous on ${userClass.name}") {
                val m = userClass.methods.firstOrNull { it.name == "isAnonymous" && it.parameterCount == 0 }
                    ?: userClass.declaredMethods.firstOrNull { it.name == "isAnonymous" && it.parameterCount == 0 }
                if (m != null) {
                    module.hookTracked(m, idPrefix = "local-cloud-user-${userClass.simpleName}-is-anonymous").intercept { chain ->
                        if (prefs.unlockLocalCloudFeatures) {
                            Log.d(TAG, "[LocalCloudUnlock] Intercepted ${userClass.name}.isAnonymous -> false")
                            return@intercept false
                        }
                        chain.proceed()
                    }
                    Log.d(TAG, "[LocalCloudUnlock] Hooked ${userClass.name}.isAnonymous")
                }
            }
        }
    }

    /**
     * Clean hook on public model companion: org.swiftapps.swiftbackup.model.app.AppCloudBackups.Companion.fetchForPackage(String)
     * Directly serves discovered cloud backups to avoid RTDB network errors during backup & restore.
     */
    fun hookAppCloudBackups(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        val companionClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups\$a")
            ?: loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups\$Companion")
            ?: return

        companionClass.declaredMethods.filter {
            it.name == "fetchForPackage" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        }.forEach { m ->
            attempt("hook AppCloudBackups.Companion.fetchForPackage") {
                module.hookTracked(m, idPrefix = "local-cloud-fetchForPackage").intercept { chain ->
                    if (!prefs.unlockLocalCloudFeatures) return@intercept chain.proceed()
                    val pkg = chain.args.getOrNull(0) as? String ?: return@intercept chain.proceed()
                    Log.d(TAG, "[LocalCloudUnlock] Intercepted AppCloudBackups.fetchForPackage($pkg)")

                    CloudDiscoveryHook.ensureScan(context, classLoader, targets)
                    val matchingApps = CloudDiscoveryHook.findMatchingBackups(pkg)

                    val appCloudBackupsClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups")
                    val resultClass = m.returnType
                    val resultCtor = resultClass.constructors.firstOrNull { it.parameterCount == 2 }

                    if (matchingApps.isNotEmpty() && appCloudBackupsClass != null && resultCtor != null) {
                        val backupsObj = CloudDiscoveryHook.buildAppCloudBackupsObject(matchingApps, classLoader)
                        if (backupsObj != null) {
                            Log.d(TAG, "[LocalCloudUnlock] Serving ${matchingApps.size} discovered backups directly for $pkg")
                            return@intercept resultCtor.newInstance(backupsObj, null)
                        }
                    }

                    if (resultCtor != null) {
                        return@intercept resultCtor.newInstance(null, null)
                    }

                    chain.proceed()
                }
                Log.i(TAG, "[LocalCloudUnlock] Hooked AppCloudBackups.Companion.fetchForPackage")
            }
        }
    }

    fun hookFireSynchronizer(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        val fireSyncClass = targets.fireSynchronizerClass ?: return
        Log.d(TAG, "[LocalCloudUnlock] Hooking dynamically resolved fireSynchronizerClass: $fireSyncClass")

        // 1. Hook readReference: method with (DatabaseReference, Boolean)
        fireSyncClass.declaredMethods.filter {
            it.parameterCount == 2 && it.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }.forEach { m ->
            attempt("hook FireSynchronizer read method (${m.name})") {
                val returnType = m.returnType
                module.hookTracked(m, idPrefix = "local-cloud-fire-sync-read").intercept { chain ->
                    if (!prefs.unlockLocalCloudFeatures) return@intercept chain.proceed()
                    val ref = chain.args.getOrNull(0) ?: return@intercept chain.proceed()
                    val path = ref.toString()
                    Log.d(TAG, "[LocalCloudUnlock] Intercepted FireSynchronizer.${m.name} for path: $path")

                    val targetPkg = extractRegexGroup(path, "apps/([^/?&#]+)", 1)
                    val snapshot = if (!targetPkg.isNullOrBlank()) {
                        CloudDiscoveryHook.ensureScan(context, classLoader, targets)
                        val matchingApps = CloudDiscoveryHook.findMatchingBackups(targetPkg)
                        if (matchingApps.isNotEmpty()) {
                            CloudDiscoveryHook.FirebaseSnapshotSynthesizer.createSyntheticSnapshot(classLoader, ref, matchingApps)
                        } else {
                            CloudDiscoveryHook.FirebaseSnapshotSynthesizer.createSnapshotFromMap(classLoader, ref, emptyMap())
                        }
                    } else if (path.contains("apps")) {
                        CloudDiscoveryHook.ensureScan(context, classLoader, targets)
                        val allApps = CloudDiscoveryHook.getAllDiscoveredApps()
                        val groupedMap = allApps.groupBy { it.sanitizedAppId }.mapValues { (_, appsForPkg) ->
                            CloudDiscoveryHook.FirebaseSnapshotSynthesizer.buildMetadataMap(appsForPkg)
                        }
                        CloudDiscoveryHook.FirebaseSnapshotSynthesizer.createSnapshotFromMap(classLoader, ref, groupedMap)
                    } else {
                        CloudDiscoveryHook.FirebaseSnapshotSynthesizer.createSnapshotFromMap(classLoader, ref, emptyMap())
                    }

                    if (snapshot != null) {
                        val successInstance = findSuccessResultInstance(returnType, snapshot)
                        if (successInstance != null) {
                            Log.d(TAG, "[LocalCloudUnlock] Returned synthetic snapshot result for path: $path")
                            return@intercept successInstance
                        }
                    }

                    chain.proceed()
                }
            }
        }

        // 2. Hook setValue: method with (DatabaseReference, Object/Any)
        fireSyncClass.declaredMethods.filter {
            it.parameterCount == 2 && it.parameterTypes[1] == Any::class.java
        }.forEach { m ->
            attempt("hook FireSynchronizer setValue (${m.name})") {
                val successVal = findStaticInstance(m.returnType)
                module.hookTracked(m, idPrefix = "local-cloud-fire-sync-setValue").intercept { chain ->
                    if (!prefs.unlockLocalCloudFeatures) return@intercept chain.proceed()
                    val ref = chain.args.getOrNull(0) ?: return@intercept chain.proceed()
                    val path = ref.toString()
                    val payload = chain.args.getOrNull(1)
                    Log.d(TAG, "[LocalCloudUnlock] Intercepted FireSynchronizer.${m.name} for path: $path")

                    if (payload != null && (path.contains("apps") || path.contains("cloud_v1"))) {
                        bgExecutor.execute {
                            dispatchMetadataToCloud(context, path, payload, classLoader)
                        }
                    }

                    if (successVal != null) {
                        return@intercept successVal
                    }
                    chain.proceed()
                }
            }
        }

        // 3. Hook runTransaction: method with (DatabaseReference, Handler)
        fireSyncClass.declaredMethods.filter {
            it.parameterCount == 2 && it.parameterTypes[1] != Boolean::class.javaPrimitiveType &&
                    it.parameterTypes[1] != Any::class.java
        }.forEach { m ->
            attempt("hook FireSynchronizer runTransaction (${m.name})") {
                val committedVal = findStaticInstance(m.returnType)
                module.hookTracked(m, idPrefix = "local-cloud-fire-sync-runTransaction").intercept { chain ->
                    if (!prefs.unlockLocalCloudFeatures) return@intercept chain.proceed()
                    val ref = chain.args.getOrNull(0) ?: return@intercept chain.proceed()
                    val path = ref.toString()
                    Log.d(TAG, "[LocalCloudUnlock] Intercepted FireSynchronizer.${m.name} for path: $path")

                    if (committedVal != null) {
                        return@intercept committedVal
                    }
                    chain.proceed()
                }
            }
        }
    }

    private fun findSuccessResultInstance(returnType: Class<*>, snapshot: Any): Any? = attempt("findSuccessResultInstance", silent = true) {
        // Find constructor taking a snapshot
        returnType.constructors.firstOrNull { it.parameterCount == 1 }?.newInstance(snapshot)
    }

    private fun findStaticInstance(targetClass: Class<*>): Any? = attempt("findStaticInstance", silent = true) {
        targetClass.declaredFields.firstOrNull { Modifier.isStatic(it.modifiers) && it.type == targetClass }?.get(null)
            ?: targetClass.fields.firstOrNull { Modifier.isStatic(it.modifiers) && it.type == targetClass }?.get(null)
    }

    fun hookDatabaseReferenceWrites(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        val companionClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups\$a")
            ?: loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups\$Companion")
        val fromSnapshotMethod = companionClass?.declaredMethods?.firstOrNull { it.name == "fromSnapshot" }
        val dataSnapshotClass = fromSnapshotMethod?.parameterTypes?.firstOrNull()

        val dbRefClass = targets.fireSynchronizerClass?.declaredMethods?.firstOrNull { it.parameterCount == 2 }?.parameterTypes?.get(0)
            ?: dataSnapshotClass?.constructors?.firstOrNull { it.parameterCount == 2 }?.parameterTypes?.firstOrNull()
            ?: loadClassFlexible(classLoader, "com.google.firebase.database.DatabaseReference")
        Log.d(TAG, "[LocalCloudUnlock] dbRefClass=$dbRefClass, dataSnapshotClass=$dataSnapshotClass")
        if (dbRefClass == null) return

        val tasksClass = loadClassFlexible(classLoader, "com.google.android.gms.tasks.Tasks")
        val successTask = attempt("create Tasks.forResult(null)", silent = true) {
            tasksClass?.getMethod("forResult", Any::class.java)?.invoke(null, null)
        }

        dbRefClass.methods.forEach { m ->
            when (m.name) {
                "setValue", "updateChildren", "i" -> attempt("hook DatabaseReference.${m.name}") {
                    module.hookTracked(m, idPrefix = "local-cloud-rtdb-write-${m.name}").intercept { chain ->
                        if (!prefs.unlockLocalCloudFeatures) return@intercept chain.proceed()

                        val path = chain.thisObject?.toString() ?: ""
                        if (!path.contains("cloud_v1") && !path.contains("apps")) return@intercept chain.proceed()

                        Log.d(TAG, "[LocalCloudUnlock] Intercepted RTDB write ${m.name} for path: $path")

                        val payload = chain.args.firstOrNull()
                        if (payload != null) {
                            bgExecutor.execute {
                                dispatchMetadataToCloud(context, path, payload, classLoader)
                            }
                        }

                        val lastArg = chain.args.lastOrNull()
                        if (lastArg != null && lastArg != payload) {
                            attempt("invoke CompletionListener.onComplete", silent = true) {
                                val onCompleteMethod = lastArg.javaClass.methods.firstOrNull {
                                    it.name == "onComplete" && it.parameterCount == 2
                                }
                                onCompleteMethod?.invoke(lastArg, null, chain.thisObject)
                            }
                        }

                        if (successTask != null && m.returnType.isInstance(successTask)) {
                            return@intercept successTask
                        }
                        if (m.returnType == Void.TYPE || m.returnType == java.lang.Void::class.java) {
                            return@intercept null
                        }
                        return@intercept successTask
                    }
                }
            }
        }
    }

    private fun dispatchMetadataToCloud(context: Context, path: String, payload: Any, classLoader: ClassLoader) {
        attempt("dispatchMetadataToCloud", silent = true) {
            val json = when (payload) {
                is JSONObject -> payload
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    JSONObject(payload as Map<String, Any?>)
                }
                else -> {
                    val obj = JSONObject()
                    for (field in payload.javaClass.declaredFields) {
                        if (Modifier.isStatic(field.modifiers)) continue
                        try {
                            field.isAccessible = true
                            val value = field.get(payload)
                            if (value != null) {
                                val fieldName = field.name.removePrefix("_")
                                obj.put(fieldName, value)
                            }
                        } catch (_: Throwable) {}
                    }
                    if (obj.length() == 0) {
                        for (method in payload.javaClass.methods) {
                            if (Modifier.isStatic(method.modifiers)) continue
                            if (method.parameterCount == 0 && (method.name.startsWith("get") || method.name.startsWith("is"))) {
                                try {
                                    val value = method.invoke(payload)
                                    if (value != null) {
                                        val propName = method.name.removePrefix("get").removePrefix("is")
                                            .replaceFirstChar { it.lowercase(java.util.Locale.ROOT) }
                                        if (propName.isNotBlank() && propName != "class") {
                                            obj.put(propName, value)
                                        }
                                    }
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                    obj
                }
            }

            var pkgName = json.optString("packageName").takeIf { it.isNotBlank() }
                ?: json.optString("pkgName").takeIf { it.isNotBlank() }
                ?: json.optString("pName").takeIf { it.isNotBlank() }
                ?: extractRegexGroup(path, "apps/([^/]+)/", 1)?.takeIf { AppUtils.isValidPackageName(it) }
                ?: extractRegexGroup(path, "apps/([^/?&#]+)", 1)?.takeIf { AppUtils.isValidPackageName(it) }

            if (pkgName == null) {
                val candAppId = json.optString("appId").takeIf { it.isNotBlank() }
                    ?: json.optString("sanitizedAppId").takeIf { it.isNotBlank() }
                    ?: extractRegexGroup(path, "apps/([^/]+)/", 1)
                    ?: extractRegexGroup(path, "apps/([^/?&#]+)", 1)

                if (!candAppId.isNullOrBlank()) {
                    pkgName = CloudDiscoveryHook.findMatchingBackups(candAppId).firstOrNull()?.packageName
                        ?: attempt("resolve pkg from installed", silent = true) {
                            context.packageManager.getInstalledPackages(0)
                                .firstOrNull { it.packageName.replace(".", "").equals(candAppId, ignoreCase = true) }
                                ?.packageName
                        }
                }
            }
            if (pkgName.isNullOrBlank()) return@attempt

            val backupId = json.optString("backupId").takeIf { it.isNotBlank() }
                ?: extractRegexGroup(path, "apps/[^/]+/([^/?&#]+)", 1)
                ?: "default"

            val sp = context.getSharedPreferences("org.swiftapps.swiftbackup_preferences", Context.MODE_PRIVATE)
            val connectedCloud = sp.getString("connected_cloud_type", null)
            val activeTag = json.optString("backupTag").takeIf { it.isNotBlank() && it != "DEFAULT" }
                ?: (if (connectedCloud != null) sp.getString("${connectedCloud}_cloud_backup_tag", null) else null)
                ?: sp.getString("google_drive_cloud_backup_tag", null)
                ?: sp.getString("cloud_backup_tag", null)
                ?: "DEFAULT"

            json.put("packageName", pkgName)
            json.put("sanitizedAppId", pkgName.replace(".", ""))
            json.put("backupId", backupId)
            json.put("backupTag", activeTag)
            if (!json.has("appName") || json.optString("appName").isBlank()) {
                val appLabel = BackupRebuilderHook.resolveAppLabel(context, pkgName)
                if (appLabel.isNotBlank()) {
                    json.put("appName", appLabel)
                }
            }

            Log.d(TAG, "[LocalCloudUnlock] Dispatching index record to cloud for pkg=$pkgName, backupId=$backupId, tag=$activeTag")
            FirebaseSyncEngine.syncAppMetadataToCloudProviders(context, pkgName, backupId, json)
        }
    }

    private fun extractRegexGroup(text: String, patternStr: String, group: Int): String? =
        try {
            val matcher = Pattern.compile(patternStr).matcher(text)
            if (matcher.find()) matcher.group(group) else null
        } catch (_: Throwable) { null }

    fun hookQueryListeners(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        val companionClass = loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups\$a")
            ?: loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.model.app.AppCloudBackups\$Companion")
        val fromSnapshotMethod = companionClass?.declaredMethods?.firstOrNull { it.name == "fromSnapshot" }
        val dataSnapshotClass = fromSnapshotMethod?.parameterTypes?.firstOrNull()

        val dbRefClass = targets.fireSynchronizerClass?.declaredMethods?.firstOrNull { it.parameterCount == 2 }?.parameterTypes?.get(0)
            ?: dataSnapshotClass?.constructors?.firstOrNull { it.parameterCount == 2 }?.parameterTypes?.firstOrNull()
            ?: loadClassFlexible(classLoader, "com.google.firebase.database.DatabaseReference")
        val queryClass = loadClassFlexible(classLoader, "com.google.firebase.database.Query")
            ?: dbRefClass?.superclass?.takeIf { it != Any::class.java }
            ?: dbRefClass
        Log.d(TAG, "[LocalCloudUnlock] queryClass=$queryClass")
        if (queryClass == null) return

        queryClass.methods.filter {
            it.name in listOf("addListenerForSingleValueEvent", "addValueEventListener", "a", "b", "c")
        }.forEach { m ->
            attempt("hook Query.${m.name}") {
                module.hookTracked(m, idPrefix = "local-cloud-query-${m.name}").intercept { chain ->
                    if (!prefs.unlockLocalCloudFeatures) return@intercept chain.proceed()

                    val query = chain.thisObject ?: return@intercept chain.proceed()
                    val path = query.toString()
                    if (!path.contains("cloud_v1") && !path.contains("apps")) return@intercept chain.proceed()

                    val listener = chain.args.firstOrNull() ?: return@intercept chain.proceed()

                    Log.d(TAG, "[LocalCloudUnlock] Intercepted Query.${m.name} for path: $path")

                    CloudDiscoveryHook.ensureScan(context, classLoader, targets)
                    val allApps = CloudDiscoveryHook.getAllDiscoveredApps()

                    val targetPkg = extractRegexGroup(path, "apps/([^/?&#]+)", 1)
                    val syntheticSnapshot = if (!targetPkg.isNullOrBlank()) {
                        val matchingApps = CloudDiscoveryHook.findMatchingBackups(targetPkg)
                        if (matchingApps.isNotEmpty()) {
                            CloudDiscoveryHook.FirebaseSnapshotSynthesizer.createSyntheticSnapshot(classLoader, query, matchingApps)
                        } else {
                            CloudDiscoveryHook.FirebaseSnapshotSynthesizer.createSnapshotFromMap(classLoader, query, emptyMap())
                        }
                    } else {
                        val groupedMap = allApps.groupBy { it.sanitizedAppId }.mapValues { (_, appsForPkg) ->
                            CloudDiscoveryHook.FirebaseSnapshotSynthesizer.buildMetadataMap(appsForPkg)
                        }
                        CloudDiscoveryHook.FirebaseSnapshotSynthesizer.createSnapshotFromMap(classLoader, query, groupedMap)
                    }

                    if (syntheticSnapshot != null) {
                        mainHandler.post {
                            attempt("dispatch synthetic onDataChange to listener") {
                                val onDataChangeMethod = listener.javaClass.methods.firstOrNull { candidate ->
                                    candidate.parameterCount == 1 && (
                                        candidate.name == "onDataChange" ||
                                        candidate.parameterTypes[0].isAssignableFrom(syntheticSnapshot.javaClass) ||
                                        syntheticSnapshot.javaClass.isAssignableFrom(candidate.parameterTypes[0])
                                    )
                                }
                                Log.d(TAG, "[LocalCloudUnlock] Dispatching synthetic onDataChange to listener: ${listener.javaClass.name}, method: ${onDataChangeMethod?.name}")
                                onDataChangeMethod?.invoke(listener, syntheticSnapshot)
                            }
                        }
                        if (m.returnType == queryClass || m.returnType.isAssignableFrom(queryClass)) {
                            return@intercept query
                        }
                        if (m.returnType.isInstance(listener)) {
                            return@intercept listener
                        }
                        return@intercept null
                    }

                    chain.proceed()
                }
            }
        }
    }
}
