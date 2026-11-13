package io.github.s1ddhants1.swiftbackupprem.hook

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import io.github.s1ddhants1.swiftbackupprem.util.loadClassFlexible
import io.github.s1ddhants1.swiftbackupprem.versionMap
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.result.ClassData
import java.lang.reflect.Modifier

@Keep
object TargetClassResolver {

    private val EXCLUDE_PACKAGES = listOf(
        "android", "androidx", "com", "iammert", "java", "javax", "kotlin", "kotlinx", "moe", "nz.mega",
        "okhttp3", "okio", "retrofit", "rikka"
    )

    @SuppressLint("NonUniqueDexKitData")
    @Suppress("DEPRECATION")
    fun resolve(ctx: Context, cl: ClassLoader, sourceDir: String): ResolvedTargets {
        var clientId: Class<*>? = null
        var v: Class<*>? = null
        var cloudGms: Class<*>? = null
        var homeVm: Class<*>? = null
        var authUser: Class<*>? = null
        var anonUser: Class<*>? = null
        var oauthHelper: Class<*>? = null
        var authRequestBuilder: Class<*>? = null
        var appBackup: Class<*>? = null
        var appMetadataXml: Class<*>? = null
        var fireSynchronizer: Class<*>? = null
        var fireSynchronizerSuccess: Class<*>? = null
        var fireSynchronizerWriteSuccess: Class<*>? = null
        var fireSynchronizerCommitted: Class<*>? = null
        var firebaseWatcher: Class<*>? = null
        var customClassMapper: Class<*>? = null
        var settingsFragment: Class<*>? = null
        var settingsDetailFragment: Class<*>? = null
        var baseSettingsFragment: Class<*>? = null
        var rootServiceManager: Class<*>? = null

        val ver = Integer.valueOf(ctx.packageManager.getPackageInfo(Consts.packageName, 0).versionCode)
        versionMap[ver]?.let { c ->
            clientId = loadClassFlexible(cl, c.clientId)
            homeVm = loadClassFlexible(cl, c.homeViewModel)
            authUser = loadClassFlexible(cl, c.authUser)
            anonUser = loadClassFlexible(cl, c.anonUser)
            oauthHelper = c.oauthHelper?.let { loadClassFlexible(cl, it) }
            authRequestBuilder = c.authRequestBuilder?.let { loadClassFlexible(cl, it) }
            appBackup = c.appBackup?.let { loadClassFlexible(cl, it) }
            appMetadataXml = c.appMetadataXml?.let { loadClassFlexible(cl, it) }
            firebaseWatcher = c.firebaseWatcher?.let { loadClassFlexible(cl, it) }
            fireSynchronizer = c.fireSynchronizer?.let { loadClassFlexible(cl, it) }
            fireSynchronizerSuccess = c.fireSynchronizerSuccess?.let { loadClassFlexible(cl, it) }
            customClassMapper = c.customClassMapper?.let { loadClassFlexible(cl, it) }
            settingsFragment = c.settingsFragment?.let { loadClassFlexible(cl, it) }
            settingsDetailFragment = c.settingsDetailFragment?.let { loadClassFlexible(cl, it) }
            baseSettingsFragment = c.baseSettingsFragment?.let { loadClassFlexible(cl, it) }
            rootServiceManager = c.rootServiceManager?.let { loadClassFlexible(cl, it) }
            cloudGms = c.cloudGms?.let { loadClassFlexible(cl, it) }
        }

        val cachePrefs = attempt("get DexKit cache preferences", silent = true) {
            ctx.getSharedPreferences("sbp_dexkit_cache", Context.MODE_PRIVATE)
        }

        if (cachePrefs != null && cachePrefs.getInt("cached_version_code", -1) == ver) {
            if (clientId == null) clientId = cachePrefs.getString("clientId", null)?.let { loadClassFlexible(cl, it) }
            if (homeVm == null) homeVm = cachePrefs.getString("homeViewModel", null)?.let { loadClassFlexible(cl, it) }
            if (authUser == null) authUser = cachePrefs.getString("authUser", null)?.let { loadClassFlexible(cl, it) }
            if (anonUser == null) anonUser = cachePrefs.getString("anonUser", null)?.let { loadClassFlexible(cl, it) }
            if (oauthHelper == null) oauthHelper = cachePrefs.getString("oauthHelper", null)?.let { loadClassFlexible(cl, it) }
            if (authRequestBuilder == null) authRequestBuilder = cachePrefs.getString("authRequestBuilder", null)?.let { loadClassFlexible(cl, it) }
            if (appBackup == null) appBackup = cachePrefs.getString("appBackup", null)?.let { loadClassFlexible(cl, it) }
            if (appMetadataXml == null) appMetadataXml = cachePrefs.getString("appMetadataXml", null)?.let { loadClassFlexible(cl, it) }
            if (firebaseWatcher == null) firebaseWatcher = cachePrefs.getString("firebaseWatcher", null)?.let { loadClassFlexible(cl, it) }
            if (fireSynchronizer == null) fireSynchronizer = cachePrefs.getString("fireSynchronizer", null)?.let { loadClassFlexible(cl, it) }
            if (fireSynchronizerSuccess == null) fireSynchronizerSuccess = cachePrefs.getString("fireSynchronizerSuccess", null)?.let { loadClassFlexible(cl, it) }
            if (fireSynchronizerWriteSuccess == null) fireSynchronizerWriteSuccess = cachePrefs.getString("fireSynchronizerWriteSuccess", null)?.let { loadClassFlexible(cl, it) }
            if (fireSynchronizerCommitted == null) fireSynchronizerCommitted = cachePrefs.getString("fireSynchronizerCommitted", null)?.let { loadClassFlexible(cl, it) }
            if (customClassMapper == null) customClassMapper = cachePrefs.getString("customClassMapper", null)?.let { loadClassFlexible(cl, it) }
            if (settingsFragment == null) settingsFragment = cachePrefs.getString("settingsFragment", null)?.let { loadClassFlexible(cl, it) }
            if (settingsDetailFragment == null) settingsDetailFragment = cachePrefs.getString("settingsDetailFragment", null)?.let { loadClassFlexible(cl, it) }
            if (baseSettingsFragment == null) baseSettingsFragment = cachePrefs.getString("baseSettingsFragment", null)?.let { loadClassFlexible(cl, it) }
            if (rootServiceManager == null) rootServiceManager = cachePrefs.getString("rootServiceManager", null)?.let { loadClassFlexible(cl, it) }
            if (cloudGms == null) cloudGms = cachePrefs.getString("cloudGms", null)?.let { loadClassFlexible(cl, it) }
        }

        attempt("load V class fallback", silent = true) {
            v = cl.loadClass("org.swiftapps.swiftbackup.common.V")
        }

        attempt("load FirebaseConnectionWatcher fallback", silent = true) {
            if (firebaseWatcher == null) {
                firebaseWatcher = cl.loadClass("org.swiftapps.swiftbackup.common.FirebaseConnectionWatcher")
            }
        }

        attempt("load RootServiceManager fallback", silent = true) {
            if (rootServiceManager == null) {
                rootServiceManager = loadClassFlexible(cl, "com.topjohnwu.superuser.internal.RootServiceManager")
            }
        }

        attempt("load CustomClassMapper fallback", silent = true) {
            if (customClassMapper == null) {
                customClassMapper = loadClassFlexible(cl, "com.google.firebase.database.core.utilities.encoding.CustomClassMapper")
            }
        }

        if (clientId != null && v != null && homeVm != null && authUser != null && oauthHelper != null && authRequestBuilder != null && fireSynchronizer != null && customClassMapper != null) {
            if (baseSettingsFragment == null) {
                baseSettingsFragment = settingsFragment?.superclass ?: settingsDetailFragment?.superclass
            }
            Log.d(Consts.TAG, "Resolved Swift Backup hook classes without DexKit scan")
            return ResolvedTargets(
                clientId, v, cloudGms, homeVm, authUser, anonUser, oauthHelper, authRequestBuilder,
                appBackup, appMetadataXml, fireSynchronizer, firebaseWatcher, fireSynchronizerSuccess,
                fireSynchronizerWriteSuccess, fireSynchronizerCommitted, customClassMapper,
                settingsFragment, settingsDetailFragment, baseSettingsFragment, rootServiceManager
            )
        }

        attempt("load dexkit native library") { System.loadLibrary("dexkit") }

        try {
            DexKitBridge.create(sourceDir).use { bridge ->
                if (clientId == null) {
                    clientId = bridge.findSingle(
                        cl,
                        "clientIdClass",
                        filterInner = false,
                        extraFilter = { cd -> cd.fields.any { Modifier.isStatic(it.modifiers) && it.typeName == "java.lang.String" } }
                    ) {
                        matcher { usingStrings("org.swiftapps.swiftbackup:/oauth") }
                    } ?: bridge.findSingle(cl, "clientIdClass by structure", filterInner = false) {
                        matcher {
                            fields {
                                add { modifiers(Modifier.PUBLIC or Modifier.STATIC or Modifier.FINAL) }
                                add { modifiers(Modifier.PRIVATE or Modifier.STATIC or Modifier.FINAL) }
                                add { modifiers(Modifier.PRIVATE or Modifier.STATIC or Modifier.FINAL); type("java.lang.String") }
                                add { modifiers(Modifier.PRIVATE or Modifier.STATIC or Modifier.FINAL); type("android.net.Uri") }
                                count(4)
                            }
                            addMethod {
                                modifiers(Modifier.PUBLIC or Modifier.FINAL)
                                returnType("android.content.Intent")
                                addParamType("boolean")
                            }
                        }
                    }
                }

                if (v == null) {
                    v = bridge.findSingle(cl, "vClass", filterInner = false) {
                        matcher { usingStrings("f4s6woi0e98") }
                    }
                }

                if (cloudGms == null) {
                    cloudGms = bridge.findSingle(cl, "cloudGmsClass", filterInner = false) {
                        matcher {
                            usingStrings("nogms_access_token")
                            addMethod {
                                name("invoke")
                                paramCount(0)
                            }
                        }
                    }
                }

                if (homeVm == null) {
                    homeVm = bridge.findSingle(cl, "homeViewModelClass (primary)", extraFilter = { !it.name.contains("AlarmReceiver") }) {
                        matcher { usingStrings("setup_cloud_first_startup", "KEY_SCHEDULE_ENABLED") }
                    } ?: bridge.findSingle(cl, "homeViewModelClass (fallback)") {
                        matcher { usingStrings("checkCloudConnectPromptNeeded=") }
                    }
                }

                if (authUser == null) {
                    authUser = bridge.findSingle(cl, "authUserClass") {
                        matcher {
                            usingStrings("clearAnonymousSignIn")
                            addMethod {
                                returnType("org.swiftapps.swiftbackup.anonymous.MFirebaseUser")
                                modifiers(Modifier.PUBLIC or Modifier.STATIC)
                                paramCount(0)
                            }
                        }
                    }
                }

                if (anonUser == null) {
                    anonUser = bridge.findSingle(cl, "anonUserClass") {
                        matcher {
                            usingStrings("anonymous@swiftbackup.app")
                            addMethod {
                                returnType("org.swiftapps.swiftbackup.anonymous.MFirebaseUser")
                                modifiers(Modifier.PUBLIC or Modifier.STATIC)
                                paramCount(0)
                            }
                        }
                    }
                }

                if (oauthHelper == null) {
                    oauthHelper = bridge.findSingle(
                        cl,
                        "oauthHelperClass",
                        filterInner = false,
                        extraFilter = { cd -> cd.fields.none { Modifier.isStatic(it.modifiers) && it.typeName == "java.lang.String" } }
                    ) {
                        matcher { usingStrings("org.swiftapps.swiftbackup:/oauth") }
                    }
                }

                if (authRequestBuilder == null) {
                    authRequestBuilder = bridge.findSingle(cl, "authRequestBuilderClass", filterInner = false) {
                        matcher { usingStrings("client ID cannot be null or empty") }
                    }
                }

                if (fireSynchronizer == null) {
                    fireSynchronizer = bridge.findSingle(
                        cl,
                        "fireSynchronizerClass",
                        filterInner = true,
                        extraFilter = { cd ->
                            cd.methods.any { m ->
                                m.paramCount == 2 &&
                                    m.paramTypeNames.getOrNull(1) == "boolean" &&
                                    m.returnTypeName != "void"
                            }
                        }
                    ) {
                        matcher { usingStrings("FireSynchronizer") }
                    }
                }

                if (fireSynchronizerSuccess == null && fireSynchronizer != null) {
                    val readMethod = fireSynchronizer.declaredMethods.firstOrNull {
                        it.parameterCount == 2 && it.parameterTypes[1] == Boolean::class.javaPrimitiveType
                    }
                    val resultBaseClass = readMethod?.returnType
                    if (resultBaseClass != null) {
                        fireSynchronizerSuccess = bridge.findSingle(cl, "fireSynchronizerSuccessClass", filterInner = false, extraFilter = { cd ->
                            !cd.name.lowercase(java.util.Locale.ROOT).contains("error")
                        }) {
                            matcher {
                                superClass(resultBaseClass.name)
                            }
                        }
                    }
                }

                if (fireSynchronizer != null) {
                    attempt("resolve FireSynchronizer result subclasses") {
                        val writeMethod = fireSynchronizer!!.declaredMethods.firstOrNull {
                            it.parameterCount == 2 && it.parameterTypes[1] == Any::class.java
                        }
                        val transactionMethod = fireSynchronizer!!.declaredMethods.firstOrNull {
                            it.parameterCount == 2 &&
                                it.parameterTypes[1] != Boolean::class.javaPrimitiveType &&
                                it.parameterTypes[1] != Any::class.java
                        }
                        val writeBase = writeMethod?.returnType
                        if (fireSynchronizerWriteSuccess == null && writeBase != null && writeBase != Any::class.java) {
                            val candidates = bridge.findClass {
                                excludePackages(EXCLUDE_PACKAGES)
                                matcher { superClass(writeBase.name) }
                            }.filter { !it.name.contains("$") && !it.name.lowercase(java.util.Locale.ROOT).contains("error") }
                            for (cd in candidates) {
                                val clazz = attempt("get class", silent = true) { cd.getInstance(cl) } ?: continue
                                if (isSuccessResultClass(clazz, writeBase)) {
                                    fireSynchronizerWriteSuccess = clazz
                                    Log.d(Consts.TAG, "Found fireSynchronizerWriteSuccessClass: ${clazz.name}")
                                    break
                                }
                            }
                        }
                        val txBase = transactionMethod?.returnType
                        if (fireSynchronizerCommitted == null && txBase != null && txBase != Any::class.java) {
                            val candidates = bridge.findClass {
                                excludePackages(EXCLUDE_PACKAGES)
                                matcher { superClass(txBase.name) }
                            }.filter { !it.name.contains("$") && !it.name.lowercase(java.util.Locale.ROOT).contains("error") }
                            for (cd in candidates) {
                                val clazz = attempt("get class", silent = true) { cd.getInstance(cl) } ?: continue
                                if (isSuccessResultClass(clazz, txBase)) {
                                    fireSynchronizerCommitted = clazz
                                    Log.d(Consts.TAG, "Found fireSynchronizerCommittedClass: ${clazz.name}")
                                    break
                                }
                            }
                        }
                    }
                }

                if (firebaseWatcher == null) {
                    firebaseWatcher = bridge.findSingle(cl, "firebaseWatcherClass", filterInner = true) {
                        matcher { usingStrings("FCW", ".info/connected") }
                    }
                }

                if (customClassMapper == null) {
                    customClassMapper = bridge.findSingle(cl, "customClassMapperClass", filterInner = false) {
                        matcher { usingStrings("Maps with non-string keys are not supported") }
                    }
                }

                if (settingsFragment == null) {
                    settingsFragment = bridge.findSingle(cl, "settingsFragmentClass", filterInner = false) {
                        matcher { usingStrings("backup_storage_location", "app_backups") }
                    }
                }

                if (settingsDetailFragment == null) {
                    settingsDetailFragment = bridge.findSingle(cl, "settingsDetailFragmentClass", filterInner = false) {
                        matcher { usingStrings("app_backup_limits", "blacklist_apps") }
                    }
                }

                if (rootServiceManager == null) {
                    rootServiceManager = bridge.findSingle(cl, "rootServiceManagerClass", filterInner = false) {
                        matcher { usingStrings("com.topjohnwu.superuser.RECEIVER_BROADCAST") }
                    }
                }

                if (baseSettingsFragment == null) {
                    baseSettingsFragment = settingsFragment?.superclass ?: settingsDetailFragment?.superclass
                }
            }
        } catch (t: Throwable) {
            Log.e(Consts.TAG, "DexKit search encountered an error", t)
        }

        if (clientId == null || homeVm == null) {
            Log.w(Consts.TAG, "Couldn't fully hook Swift Backup.")
        }

        if (baseSettingsFragment == null) {
            baseSettingsFragment = settingsFragment?.superclass ?: settingsDetailFragment?.superclass
        }

        attempt("save DexKit cache", silent = true) {
            if (cachePrefs != null && clientId != null && homeVm != null && authUser != null) {
                cachePrefs.edit()
                    .putInt("cached_version_code", ver)
                    .putString("clientId", clientId.name)
                    .putString("homeViewModel", homeVm.name)
                    .putString("authUser", authUser.name)
                    .putString("anonUser", anonUser?.name)
                    .putString("oauthHelper", oauthHelper?.name)
                    .putString("authRequestBuilder", authRequestBuilder?.name)
                    .putString("appBackup", appBackup?.name)
                    .putString("appMetadataXml", appMetadataXml?.name)
                    .putString("firebaseWatcher", firebaseWatcher?.name)
                    .putString("fireSynchronizer", fireSynchronizer?.name)
                    .putString("fireSynchronizerSuccess", fireSynchronizerSuccess?.name)
                    .putString("fireSynchronizerWriteSuccess", fireSynchronizerWriteSuccess?.name)
                    .putString("fireSynchronizerCommitted", fireSynchronizerCommitted?.name)
                    .putString("customClassMapper", customClassMapper?.name)
                    .putString("settingsFragment", settingsFragment?.name)
                    .putString("settingsDetailFragment", settingsDetailFragment?.name)
                    .putString("baseSettingsFragment", baseSettingsFragment?.name)
                    .putString("rootServiceManager", rootServiceManager?.name)
                    .putString("cloudGms", cloudGms?.name)
                    .apply()
                Log.d(Consts.TAG, "Saved resolved hook classes to DexKit disk cache for version $ver")
            }
        }

        return ResolvedTargets(
            clientId, v, cloudGms, homeVm, authUser, anonUser, oauthHelper, authRequestBuilder,
            appBackup, appMetadataXml, fireSynchronizer, firebaseWatcher, fireSynchronizerSuccess,
            fireSynchronizerWriteSuccess, fireSynchronizerCommitted, customClassMapper,
            settingsFragment, settingsDetailFragment, baseSettingsFragment, rootServiceManager
        )
    }

    private fun DexKitBridge.findSingle(
        cl: ClassLoader,
        label: String,
        filterInner: Boolean = true,
        extraFilter: ((ClassData) -> Boolean)? = null,
        builder: FindClass.() -> Unit
    ): Class<*>? {
        val candidates = findClass {
            excludePackages(EXCLUDE_PACKAGES)
            builder()
        }
        var filtered = if (filterInner) candidates.filter { !it.name.contains("$") } else candidates
        if (extraFilter != null) {
            filtered = filtered.filter(extraFilter)
        }
        Log.d(Consts.TAG, "Found ${candidates.size} candidate(s) (${filtered.size} filtered) for $label")
        val selected = when (filtered.size) {
            0 -> null
            1 -> filtered.single()
            else -> {
                Log.w(Consts.TAG, "Multiple $label candidates: ${filtered.map { it.name }}")
                filtered.firstOrNull()
            }
        }
        return selected?.getInstance(cl)?.also {
            Log.d(Consts.TAG, "Found $label: ${it.name}")
        }
    }

    private fun isSuccessResultClass(cls: Class<*>, baseClass: Class<*>): Boolean {
        if (!baseClass.isAssignableFrom(cls)) return false
        val name = cls.name.lowercase(java.util.Locale.ROOT)
        if (name.contains("error") || name.contains("fail") || name.contains("abort")) return false
        for (ctor in cls.declaredConstructors) {
            if (ctor.parameterTypes.any { Throwable::class.java.isAssignableFrom(it) }) return false
        }
        return true
    }
}
