package io.github.s1ddhants1.swiftbackupprem.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.Keep
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.hook.experimental.BackupRebuilderHook
import io.github.s1ddhants1.swiftbackupprem.hook.experimental.CloudDatabaseManager
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

@Keep
object LocalCloudUnlockHook : HookHandler {

    @Volatile
    private var cachedAuthState: Pair<Long, Boolean>? = null

    fun clearAuthCache() {
        cachedAuthState = null
    }

    fun shutdown() {
        clearAuthCache()
    }

    fun isGoogleUserSignedIn(context: Context?, classLoader: ClassLoader? = null): Boolean {
        val cl = classLoader ?: context?.classLoader ?: ClassLoader.getSystemClassLoader()
        val now = System.currentTimeMillis()
        cachedAuthState?.let { (timestamp, signedIn) ->
            if (now - timestamp in 0..1000L) {
                return signedIn
            }
        }

        val signedIn = detectGoogleUserSignedIn(context, cl)
        cachedAuthState = Pair(now, signedIn)
        return signedIn
    }

    fun detectGoogleUserSignedIn(context: Context?, classLoader: ClassLoader? = null): Boolean {
        val cl = classLoader ?: context?.classLoader ?: ClassLoader.getSystemClassLoader()
        val fbAuthUserSignedIn = attempt("check FirebaseAuth currentUser", silent = true) {
            val fbAuthClass = loadClassFlexible(cl, "com.google.firebase.auth.FirebaseAuth") ?: return@attempt false
            val authInstance = fbAuthClass.getDeclaredMethod("getInstance").invoke(null) ?: return@attempt false
            val currentUser = fbAuthClass.getDeclaredMethod("getCurrentUser").invoke(authInstance) ?: return@attempt false

            if (isAnonymousUserInstance(currentUser)) return@attempt false

            val providerData = currentUser.javaClass.methods.firstOrNull { it.name == "getProviderData" && it.parameterCount == 0 }
                ?.invoke(currentUser) as? List<*>
            if (providerData != null) {
                for (info in providerData) {
                    val pid = info?.javaClass?.methods?.firstOrNull { it.name == "getProviderId" && it.parameterCount == 0 }
                        ?.invoke(info) as? String
                    if (pid == "google.com") return@attempt true
                }
            }

            val email = currentUser.javaClass.methods.firstOrNull { it.name == "getEmail" && it.parameterCount == 0 }
                ?.invoke(currentUser) as? String
            if (!email.isNullOrBlank() && email != "anonymous@swiftbackup.app" && email.contains("@")) {
                return@attempt true
            }

            false
        } ?: false

        if (fbAuthUserSignedIn) return true

        if (context != null) {
            val spSignedIn = attempt("check Swift Backup preferences for google user", silent = true) {
                val sp = context.getSharedPreferences("org.swiftapps.swiftbackup_preferences", Context.MODE_PRIVATE)
                for ((_, value) in sp.all) {
                    val str = value as? String ?: continue
                    if (str.startsWith("{") && str.contains("\"isAnonymous\"")) {
                        try {
                            val obj = JSONObject(str)
                            val isAnon = obj.optBoolean("isAnonymous", false)
                            val email = obj.optString("email")
                            val providerId = obj.optString("providerId")
                            if (!isAnon && email.isNotBlank() && email != "anonymous@swiftbackup.app" &&
                                (providerId == "google.com" || email.contains("@"))
                            ) {
                                return@attempt true
                            }
                        } catch (_: Throwable) {}
                    }
                    if (str.contains("nogms_auth_state") || str.contains("lastAuthorizationResponse")) {
                        return@attempt true
                    }
                }
                false
            } ?: false

            if (spSignedIn) return true
        }

        return false
    }

    fun shouldEnforceLocalCloud(
        prefs: PreferencesManager,
        context: Context?,
        classLoader: ClassLoader? = null
    ): Boolean {
        if (!prefs.unlockLocalCloudFeatures) return false
        if (!prefs.customFirebaseApp) return true
        return !isGoogleUserSignedIn(context, classLoader)
    }

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
        hookIsAnonymous(module, classLoader, targets, prefs, context)
        hookGetUid(module, classLoader, targets, prefs, context)
        hookFirebaseWatcher(module, classLoader, targets, prefs, context)
        hookFireSynchronizer(module, context, classLoader, targets, prefs)
        hookDatabaseReferenceWrites(module, context, classLoader, targets, prefs)
        hookQueryListeners(module, context, classLoader, targets, prefs)
    }

    fun hookGetUid(
        module: XposedModule,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager,
        context: Context? = null
    ) {
        val userClasses = listOfNotNull(
            loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.anonymous.MFirebaseUser"),
            loadClassFlexible(classLoader, "com.google.firebase.auth.FirebaseUser"),
            targets.authUserClass,
            targets.anonUserClass
        ).distinct()

        for (userCls in userClasses) {
            attempt("hook ${userCls.simpleName}.getUid") {
                val m = userCls.methods.firstOrNull { it.name == "getUid" && it.parameterCount == 0 && it.returnType == String::class.java }
                    ?: userCls.declaredMethods.firstOrNull { it.name == "getUid" && it.parameterCount == 0 && it.returnType == String::class.java }
                if (m != null) {
                    module.hookTracked(m, idPrefix = "local-cloud-${userCls.simpleName}-get-uid").intercept { chain ->
                        val enforceLocal = shouldEnforceLocalCloud(prefs, context, classLoader)
                        val customUid = prefs.localAccountCustomUid.trim().takeIf { it.isNotEmpty() }
                            ?: CloudDatabaseManager.getPrimaryUid()
                        if (enforceLocal && !customUid.isNullOrBlank()) {
                            if (isAnonymousUserInstance(chain.thisObject)) {
                                return@intercept customUid
                            }
                        }
                        chain.proceed()
                    }
                    Log.i(TAG, "[LocalCloudUnlock] Hooked ${userCls.name}.getUid for custom UID")
                }
            }
        }

        targets.anonUserClass?.let { anonCls ->
            attempt("hook ${anonCls.simpleName} anonymous user factory") {
                val factoryMethod = anonCls.declaredMethods.firstOrNull {
                    Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                        it.returnType.name.contains("MFirebaseUser")
                } ?: anonCls.methods.firstOrNull {
                    Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                        it.returnType.name.contains("MFirebaseUser")
                }
                if (factoryMethod != null) {
                    module.hookTracked(factoryMethod, idPrefix = "local-cloud-anon-factory").intercept { chain ->
                        val result = chain.proceed()
                        val enforceLocal = shouldEnforceLocalCloud(prefs, context, classLoader)
                        val customUid = prefs.localAccountCustomUid.trim().takeIf { it.isNotEmpty() }
                            ?: CloudDatabaseManager.getPrimaryUid()
                        if (enforceLocal && !customUid.isNullOrBlank() && result != null) {
                            try {
                                val uidField = result.javaClass.declaredFields.firstOrNull { it.name == "uid" }
                                    ?: result.javaClass.fields.firstOrNull { it.name == "uid" }
                                if (uidField != null) {
                                    uidField.isAccessible = true
                                    uidField.set(result, customUid)
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "[LocalCloudUnlock] Failed to set custom UID on MFirebaseUser instance", t)
                            }
                        }
                        result
                    }
                    Log.i(TAG, "[LocalCloudUnlock] Hooked ${anonCls.name}.${factoryMethod.name} factory")
                }
            }
        }
    }

    fun isAnonymousUserInstance(userObj: Any?): Boolean {
        if (userObj == null) return false
        return try {
            val cls = userObj.javaClass

            val emailField = cls.declaredFields.firstOrNull { it.name == "email" }
            if (emailField != null) {
                emailField.isAccessible = true
                val email = emailField.get(userObj) as? String
                if (email == "anonymous@swiftbackup.app") return true
            }

            val providerField = cls.declaredFields.firstOrNull { it.name == "providerId" }
            if (providerField != null) {
                providerField.isAccessible = true
                val provider = providerField.get(userObj) as? String
                if (provider == "anonymous") return true
            }

            val isAnonField = cls.declaredFields.firstOrNull {
                it.name == "isAnonymous" || (it.type == Boolean::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers))
            }
            if (isAnonField != null) {
                isAnonField.isAccessible = true
                if (isAnonField.getBoolean(userObj)) return true
            }

            val uidField = cls.declaredFields.firstOrNull { it.name == "uid" }
            if (uidField != null) {
                uidField.isAccessible = true
                val uid = uidField.get(userObj) as? String
                if (uid == "d58b0944415a4889d7f11aa95fbeca50") return true
            }

            false
        } catch (_: Throwable) {
            false
        }
    }

    fun hookIsAnonymous(
        module: XposedModule,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager,
        context: Context? = null
    ) {
        val watcherClassName = targets.firebaseWatcherClass?.name
        val userClasses = listOfNotNull(
            loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.anonymous.MFirebaseUser"),
            loadClassFlexible(classLoader, "com.google.firebase.auth.FirebaseUser"),
            targets.authUserClass,
            targets.anonUserClass
        ).distinct()

        for (userCls in userClasses) {
            attempt("hook ${userCls.simpleName}.isAnonymous") {
                val m = userCls.methods.firstOrNull { it.name == "isAnonymous" && it.parameterCount == 0 }
                    ?: userCls.declaredMethods.firstOrNull { it.name == "isAnonymous" && it.parameterCount == 0 }
                if (m != null) {
                    module.hookTracked(m, idPrefix = "local-cloud-${userCls.simpleName}-is-anonymous").intercept { chain ->
                        val enforceLocal = shouldEnforceLocalCloud(prefs, context, classLoader)
                        if (enforceLocal) {
                            if (!prefs.customFirebaseApp && shouldSkipIsAnonymousSpoof(watcherClassName)) {
                                return@intercept chain.proceed()
                            }
                            Log.d(TAG, "[LocalCloudUnlock] Intercepted ${userCls.simpleName}.isAnonymous -> false")
                            return@intercept false
                        }
                        chain.proceed()
                    }
                    Log.i(TAG, "[LocalCloudUnlock] Hooked ${userCls.name}.isAnonymous")
                }
            }
        }
    }

    fun shouldSkipIsAnonymousSpoof(watcherClassName: String?): Boolean {
        return try {
            val stack = Thread.currentThread().stackTrace
            for (i in 2 until minOf(stack.size, 15)) {
                val cls = stack[i].className
                if (cls.contains("org.swiftapps.swiftbackup.intro") || (watcherClassName != null && cls == watcherClassName)) {
                    return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    fun hookFirebaseWatcher(
        module: XposedModule,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager,
        context: Context? = null
    ) {
        val watcherClass = targets.firebaseWatcherClass
            ?: loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.common.FirebaseConnectionWatcher")
            ?: return

        attempt("hook FirebaseConnectionWatcher isApplicable") {
            val isApplicableMethod = watcherClass.declaredMethods.firstOrNull { m ->
                Modifier.isStatic(m.modifiers) &&
                    m.parameterCount == 0 &&
                    (m.returnType == Boolean::class.javaPrimitiveType || m.returnType == Boolean::class.javaObjectType)
            }
            if (isApplicableMethod != null) {
                module.hookTracked(
                    isApplicableMethod,
                    idPrefix = "local-cloud-fcw-is-applicable",
                    deoptimize = true
                ).intercept { chain ->
                    val enforceLocal = shouldEnforceLocalCloud(prefs, context, classLoader)
                    if (enforceLocal) {
                        Log.d(TAG, "[LocalCloudUnlock] Intercepted ${watcherClass.simpleName}.${isApplicableMethod.name}() -> false (suppressing backend checks for local account)")
                        return@intercept false
                    }
                    chain.proceed()
                }
                Log.i(TAG, "[LocalCloudUnlock] Hooked ${watcherClass.name}.${isApplicableMethod.name} (FirebaseWatcher isApplicable)")
            }
        }

        attempt("hook FirebaseConnectionWatcher dialog creators") {
            watcherClass.declaredMethods.filter { m ->
                Modifier.isStatic(m.modifiers) && android.app.Dialog::class.java.isAssignableFrom(m.returnType)
            }.forEach { m ->
                module.hookTracked(
                    m,
                    idPrefix = "local-cloud-fcw-dialog-${m.name}",
                    deoptimize = true
                ).intercept { chain ->
                    val enforceLocal = shouldEnforceLocalCloud(prefs, context, classLoader)
                    if (enforceLocal) {
                        Log.d(TAG, "[LocalCloudUnlock] Intercepted ${watcherClass.simpleName}.${m.name}() dialog -> null")
                        return@intercept null
                    }
                    chain.proceed()
                }
                Log.i(TAG, "[LocalCloudUnlock] Hooked dialog creator ${watcherClass.name}.${m.name}")
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

        fireSyncClass.declaredMethods.filter {
            it.parameterCount == 2 && it.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }.forEach { m ->
            attempt("hook FireSynchronizer read method (${m.name})") {
                val returnType = m.returnType
                module.hookTracked(m, idPrefix = "local-cloud-fire-sync-read").intercept { chain ->
                    val enforceLocal = shouldEnforceLocalCloud(prefs, context, classLoader)
                    if (!enforceLocal) return@intercept chain.proceed()
                    val ref = chain.args.getOrNull(0) ?: return@intercept chain.proceed()
                    val path = ref.toString()
                    Log.d(TAG, "[LocalCloudUnlock] Intercepted FireSynchronizer.${m.name} for path: $path")

                    val snapshot = CloudDiscoveryHook.FirebaseSnapshotSynthesizer.createSnapshotForPath(
                        classLoader, ref, path, context, targets, prefs
                    )

                    if (snapshot != null) {
                        val successInstance = findSuccessResultInstance(returnType, snapshot, classLoader, targets)
                        if (successInstance != null) {
                            Log.d(TAG, "[LocalCloudUnlock] Returned authentic snapshot result for path: $path")
                            return@intercept successInstance
                        }
                    }

                    val emptySnapshot = CloudDiscoveryHook.FirebaseSnapshotSynthesizer.createSnapshotFromMap(
                        classLoader, ref, emptyMap<String, Any>()
                    )
                    if (emptySnapshot != null) {
                        val emptySuccess = findSuccessResultInstance(returnType, emptySnapshot, classLoader, targets)
                        if (emptySuccess != null) {
                            Log.d(TAG, "[LocalCloudUnlock] Returned empty snapshot result for unresolved path: $path")
                            return@intercept emptySuccess
                        }
                    }
                    chain.proceed()
                }
            }
        }

        fireSyncClass.declaredMethods.filter {
            it.parameterCount == 2 && it.parameterTypes[1] == Any::class.java
        }.forEach { m ->
            attempt("hook FireSynchronizer setValue (${m.name})") {
                module.hookTracked(m, idPrefix = "local-cloud-fire-sync-setValue").intercept { chain ->
                    val enforceLocal = shouldEnforceLocalCloud(prefs, context, classLoader)
                    if (!enforceLocal) return@intercept chain.proceed()
                    val ref = chain.args.getOrNull(0) ?: return@intercept chain.proceed()
                    val path = ref.toString()
                    val payload = chain.args.getOrNull(1)
                    Log.d(TAG, "[LocalCloudUnlock] Intercepted FireSynchronizer.${m.name} for path: $path")

                    if (payload != null) {
                        CloudDatabaseManager.updateDbFromWrite(path, payload, context, prefs)
                        if (path.contains("apps") || path.contains("cloud_v1")) {
                            bgExecutor.execute {
                                dispatchMetadataToCloud(context, path, payload, classLoader)
                            }
                        }
                    }

                    try {
                        val result = chain.proceed()
                        if (result != null && isSuccessInstance(result)) {
                            Log.d(TAG, "[LocalCloudUnlock] FireSynchronizer.${m.name} completed naturally with: $result")
                            return@intercept result
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "[LocalCloudUnlock] FireSynchronizer.${m.name} natural execution failed", t)
                    }

                    val fallbackSuccess = getAuthenticSuccessInstance(m.returnType, classLoader)
                    if (fallbackSuccess != null) {
                        return@intercept fallbackSuccess
                    }
                    chain.proceed()
                }
            }
        }

        fireSyncClass.declaredMethods.filter {
            it.parameterCount == 2 && it.parameterTypes[1] != Boolean::class.javaPrimitiveType &&
                    it.parameterTypes[1] != Any::class.java
        }.forEach { m ->
            attempt("hook FireSynchronizer runTransaction (${m.name})") {
                module.hookTracked(m, idPrefix = "local-cloud-fire-sync-runTransaction").intercept { chain ->
                    val enforceLocal = shouldEnforceLocalCloud(prefs, context, classLoader)
                    if (!enforceLocal) return@intercept chain.proceed()
                    val ref = chain.args.getOrNull(0) ?: return@intercept chain.proceed()
                    val path = ref.toString()
                    Log.d(TAG, "[LocalCloudUnlock] Intercepted FireSynchronizer.${m.name} for path: $path")

                    val committedVal = getAuthenticCommittedInstance(m.returnType, classLoader)
                    if (committedVal != null) {
                        return@intercept committedVal
                    }
                    chain.proceed()
                }
            }
        }
    }

    private fun findSuccessResultInstance(
        returnType: Class<*>,
        snapshot: Any,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ): Any? = attempt("findSuccessResultInstance", silent = true) {
        val successClass = targets.fireSynchronizerSuccessClass?.takeIf { isSuccessClass(it, returnType) }
            ?: loadClassFlexible(classLoader, "defpackage.xe3")
            ?: returnType.declaredClasses.firstOrNull { returnType.isAssignableFrom(it) && isSuccessClass(it, returnType) }

        if (successClass != null) {
            val ctor = successClass.constructors.firstOrNull { it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(snapshot.javaClass) }
            if (ctor != null) {
                ctor.isAccessible = true
                return@attempt ctor.newInstance(snapshot)
            }
        }
        returnType.constructors.firstOrNull { it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(snapshot.javaClass) }?.newInstance(snapshot)
    }

    fun isSuccessClass(cls: Class<*>, targetClass: Class<*>): Boolean {
        if (!targetClass.isAssignableFrom(cls)) return false
        val name = cls.name.lowercase(java.util.Locale.ROOT)
        if (name.contains("error") || name.contains("fail") || name.contains("abort") ||
            name.contains("ye3") || name.contains("we3") || name.contains("ue3")) {
            return false
        }

        for (ctor in cls.declaredConstructors) {
            if (ctor.parameterTypes.any { Throwable::class.java.isAssignableFrom(it) || it.name.contains("wc2") }) {
                return false
            }
        }
        return true
    }

    fun isSuccessInstance(instance: Any?): Boolean {
        if (instance == null) return false
        val str = try { instance.toString().lowercase(java.util.Locale.ROOT) } catch (_: Throwable) { "" }
        if (str.contains("error") || str.contains("fail") || str.contains("abort")) {
            return false
        }
        val className = instance.javaClass.name.lowercase(java.util.Locale.ROOT)
        if (className.contains("ye3") || className.contains("we3") || className.contains("ue3")) {
            return false
        }

        var curr: Class<*>? = instance.javaClass
        while (curr != null && curr != Any::class.java) {
            for (f in curr.declaredFields) {
                if (!Modifier.isStatic(f.modifiers)) {
                    if (f.type == Boolean::class.javaPrimitiveType) {
                        try {
                            f.isAccessible = true
                            if (!f.getBoolean(instance)) {
                                return false
                            }
                        } catch (_: Throwable) {}
                    }
                    if (Throwable::class.java.isAssignableFrom(f.type) || f.type.name.contains("wc2")) {
                        return false
                    }
                }
            }
            curr = curr.superclass
        }
        return true
    }

    fun getAuthenticSuccessInstance(targetClass: Class<*>, classLoader: ClassLoader? = null): Any? =
        attempt("getAuthenticSuccessInstance", silent = true) {
            if (classLoader != null) {
                val ze3Class = loadClassFlexible(classLoader, "defpackage.ze3")
                if (ze3Class != null && targetClass.isAssignableFrom(ze3Class) && isSuccessClass(ze3Class, targetClass)) {
                    val fieldB = ze3Class.declaredFields.firstOrNull { Modifier.isStatic(it.modifiers) && ze3Class.isAssignableFrom(it.type) }
                    if (fieldB != null) {
                        fieldB.isAccessible = true
                        val inst = fieldB.get(null)
                        if (isSuccessInstance(inst)) return@attempt inst
                    }
                    val ctor = ze3Class.declaredConstructors.firstOrNull { it.parameterCount == 1 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType }
                    if (ctor != null) {
                        ctor.isAccessible = true
                        val inst = ctor.newInstance(true)
                        if (isSuccessInstance(inst)) return@attempt inst
                    }
                }
            }
            val candidateInnerClasses = targetClass.declaredClasses.filter {
                targetClass.isAssignableFrom(it) && isSuccessClass(it, targetClass)
            }
            for (inner in candidateInnerClasses) {
                val fieldB = inner.declaredFields.firstOrNull { Modifier.isStatic(it.modifiers) && targetClass.isAssignableFrom(it.type) }
                if (fieldB != null) {
                    fieldB.isAccessible = true
                    val inst = fieldB.get(null)
                    if (isSuccessInstance(inst)) return@attempt inst
                }
                val ctor = inner.declaredConstructors.firstOrNull { it.parameterCount == 1 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType }
                if (ctor != null) {
                    ctor.isAccessible = true
                    val inst = ctor.newInstance(true)
                    if (isSuccessInstance(inst)) return@attempt inst
                }
            }
            null
        }

    fun getAuthenticCommittedInstance(targetClass: Class<*>, classLoader: ClassLoader? = null): Any? =
        attempt("getAuthenticCommittedInstance", silent = true) {
            if (classLoader != null) {
                val te3Class = loadClassFlexible(classLoader, "defpackage.te3")
                if (te3Class != null && targetClass.isAssignableFrom(te3Class) && isSuccessClass(te3Class, targetClass)) {
                    val fieldA = te3Class.declaredFields.firstOrNull { Modifier.isStatic(it.modifiers) && te3Class.isAssignableFrom(it.type) }
                    if (fieldA != null) {
                        fieldA.isAccessible = true
                        val inst = fieldA.get(null)
                        if (isSuccessInstance(inst)) return@attempt inst
                    }
                    val ctor = te3Class.declaredConstructors.firstOrNull { it.parameterCount == 0 }
                    if (ctor != null) {
                        ctor.isAccessible = true
                        val inst = ctor.newInstance()
                        if (isSuccessInstance(inst)) return@attempt inst
                    }
                }
            }
            val candidateInnerClasses = targetClass.declaredClasses.filter {
                targetClass.isAssignableFrom(it) && isSuccessClass(it, targetClass)
            }
            for (inner in candidateInnerClasses) {
                val fieldA = inner.declaredFields.firstOrNull { Modifier.isStatic(it.modifiers) && targetClass.isAssignableFrom(it.type) }
                if (fieldA != null) {
                    fieldA.isAccessible = true
                    val inst = fieldA.get(null)
                    if (isSuccessInstance(inst)) return@attempt inst
                }
                val ctor = inner.declaredConstructors.firstOrNull { it.parameterCount == 0 }
                if (ctor != null) {
                    ctor.isAccessible = true
                    val inst = ctor.newInstance()
                    if (isSuccessInstance(inst)) return@attempt inst
                }
            }
            null
        }

    fun findSuccessInstance(targetClass: Class<*>, classLoader: ClassLoader? = null): Any? =
        getAuthenticSuccessInstance(targetClass, classLoader)

    fun findStaticInstance(targetClass: Class<*>, classLoader: ClassLoader? = null): Any? =
        getAuthenticSuccessInstance(targetClass, classLoader)

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
                "setValue", "updateChildren", "removeValue", "i" -> attempt("hook DatabaseReference.${m.name}") {
                    module.hookTracked(m, idPrefix = "local-cloud-rtdb-write-${m.name}").intercept { chain ->
                        val enforceLocal = shouldEnforceLocalCloud(prefs, context, classLoader)
                        if (!enforceLocal) return@intercept chain.proceed()

                        val path = chain.thisObject?.toString() ?: ""
                        Log.d(TAG, "[LocalCloudUnlock] Intercepted RTDB write ${m.name} for path: $path")

                        val payload = if (m.name == "removeValue") null else chain.args.firstOrNull()
                        CloudDatabaseManager.updateDbFromWrite(path, payload, context, prefs)
                        if (payload != null && (path.contains("apps") || path.contains("cloud_v1"))) {
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
                ?: return@attempt

            val sp = context.getSharedPreferences("org.swiftapps.swiftbackup_preferences", Context.MODE_PRIVATE)
            val connectedCloud = sp.getString("connected_cloud_type", null)
            val activeTag = json.optString("backupTag").takeIf { it.isNotBlank() }
                ?: (if (connectedCloud != null) sp.getString("${connectedCloud}_cloud_backup_tag", null) else null)
                ?: sp.getString("google_drive_cloud_backup_tag", null)
                ?: sp.getString("cloud_backup_tag", null)
                ?: "DEFAULT"

            json.put("packageName", pkgName)
            json.put("sanitizedAppId", pkgName.replace(".", ""))
            json.put("backupId", backupId)
            if (json.optString("backupTag").isBlank()) {
                json.put("backupTag", activeTag)
            }
            if (!json.has("appName") || json.optString("appName").isBlank()) {
                val appLabel = BackupRebuilderHook.resolveAppLabel(context, pkgName)
                if (appLabel.isNotBlank()) {
                    json.put("appName", appLabel)
                }
            }

            Log.d(TAG, "[LocalCloudUnlock] Dispatching index record to cloud for pkg=$pkgName, backupId=$backupId, tag=$activeTag")
            attempt("upsert backup into discovery index") {
                val existingProvider = CloudDiscoveryHook.findMatchingBackups(pkgName)
                    .firstOrNull { it.backupId == backupId }?.provider
                val app = CloudDiscoveryHook.DiscoveredCloudApp.fromJson(pkgName, json)
                CloudDiscoveryHook.addDiscoveredBackup(
                    if (existingProvider != null) app.copy(provider = existingProvider) else app
                )
            }
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
            it.parameterCount == 1 && !it.parameterTypes[0].isPrimitive &&
            it.name in listOf("addListenerForSingleValueEvent", "addValueEventListener", "b", "c")
        }.forEach { m ->
            attempt("hook Query.${m.name}") {
                module.hookTracked(m, idPrefix = "local-cloud-query-${m.name}").intercept { chain ->
                    val enforceLocal = shouldEnforceLocalCloud(prefs, context, classLoader)
                    if (!enforceLocal) return@intercept chain.proceed()

                    val query = chain.thisObject ?: return@intercept chain.proceed()
                    val path = query.toString()
                    val listener = chain.args.firstOrNull() ?: return@intercept chain.proceed()

                    Log.d(TAG, "[LocalCloudUnlock] Intercepted Query.${m.name} for path: $path")

                    val syntheticSnapshot = CloudDiscoveryHook.FirebaseSnapshotSynthesizer.createSnapshotForPath(
                        classLoader, query, path, context, targets, prefs
                    )

                    if (syntheticSnapshot != null) {
                        val isSingleShot = m.returnType == Void.TYPE || m.returnType == java.lang.Void::class.java
                        if (!isSingleShot) {
                            val proceedResult = try {
                                chain.proceed()
                            } catch (_: Throwable) {
                                null
                            }
                            mainHandler.post {
                                attempt("dispatch synthetic onDataChange to listener") {
                                    val onDataChangeMethod = listener.javaClass.methods.firstOrNull { candidate ->
                                        candidate.parameterCount == 1 && candidate.name != "equals" && (
                                            candidate.name == "onDataChange" ||
                                            candidate.parameterTypes[0].isAssignableFrom(syntheticSnapshot.javaClass) ||
                                            syntheticSnapshot.javaClass.isAssignableFrom(candidate.parameterTypes[0])
                                        )
                                    }
                                    Log.d(TAG, "[LocalCloudUnlock] Dispatching synthetic onDataChange to listener: ${listener.javaClass.name}, method: ${onDataChangeMethod?.name}")
                                    onDataChangeMethod?.invoke(listener, syntheticSnapshot)
                                }
                            }
                            if (proceedResult != null) return@intercept proceedResult
                            if (m.returnType == queryClass || m.returnType.isAssignableFrom(queryClass)) {
                                return@intercept query
                            }
                            if (m.returnType.isInstance(listener)) {
                                return@intercept listener
                            }
                            return@intercept null
                        }
                        mainHandler.post {
                            attempt("dispatch synthetic onDataChange to listener") {
                                val onDataChangeMethod = listener.javaClass.methods.firstOrNull { candidate ->
                                    candidate.parameterCount == 1 && candidate.name != "equals" && (
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

                    val emptySnapshot = CloudDiscoveryHook.FirebaseSnapshotSynthesizer.createSnapshotFromMap(
                        classLoader, query, emptyMap<String, Any>()
                    )
                    if (emptySnapshot != null) {
                        mainHandler.post {
                            attempt("dispatch empty onDataChange to listener") {
                                val onDataChangeMethod = listener.javaClass.methods.firstOrNull { candidate ->
                                    candidate.parameterCount == 1 && candidate.name != "equals" && (
                                        candidate.name == "onDataChange" ||
                                        candidate.parameterTypes[0].isAssignableFrom(emptySnapshot.javaClass) ||
                                        emptySnapshot.javaClass.isAssignableFrom(candidate.parameterTypes[0])
                                    )
                                }
                                Log.d(TAG, "[LocalCloudUnlock] Dispatching empty onDataChange for unresolved path: $path")
                                onDataChangeMethod?.invoke(listener, emptySnapshot)
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
