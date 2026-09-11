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
        var firebaseWatcher: Class<*>? = null

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
        }

        attempt("load V class fallback", silent = true) {
            v = cl.loadClass("org.swiftapps.swiftbackup.common.V")
        }

        attempt("load FirebaseConnectionWatcher fallback", silent = true) {
            if (firebaseWatcher == null) {
                firebaseWatcher = cl.loadClass("org.swiftapps.swiftbackup.common.FirebaseConnectionWatcher")
            }
        }

        for (name in listOf("org.swiftapps.swiftbackup.cloud.d0", "org.swiftapps.swiftbackup.cloud.d")) {
            cloudGms = attempt("load cloud Gms class ($name)", silent = true) { cl.loadClass(name) }
            if (cloudGms != null) break
        }

        if (clientId != null && v != null && homeVm != null && authUser != null && oauthHelper != null && authRequestBuilder != null && fireSynchronizer != null) {
            Log.d(Consts.TAG, "Resolved Swift Backup hook classes without DexKit scan")
            return ResolvedTargets(clientId, v, cloudGms, homeVm, authUser, anonUser, oauthHelper, authRequestBuilder, appBackup, appMetadataXml, fireSynchronizer, firebaseWatcher, fireSynchronizerSuccess)
        }

        attempt("load dexkit native library") { System.loadLibrary("dexkit") }

        try {
            DexKitBridge.create(sourceDir).use { bridge ->
                if (clientId == null) {
                    clientId = bridge.findSingle(cl, "clientIdClass", filterInner = false) {
                        matcher { usingStrings("org.swiftapps.swiftbackup:/oauth") }
                    } ?: bridge.findSingle(cl, "clientIdClass by structure", filterInner = false) {
                        matcher {
                            fields {
                                add { modifiers(Modifier.PUBLIC or Modifier.STATIC or Modifier.FINAL); name("a") }
                                add { modifiers(Modifier.PRIVATE or Modifier.STATIC or Modifier.FINAL); name("b") }
                                add { modifiers(Modifier.PRIVATE or Modifier.STATIC or Modifier.FINAL); name("c"); type("java.lang.String") }
                                add { modifiers(Modifier.PRIVATE or Modifier.STATIC or Modifier.FINAL); name("d"); type("android.net.Uri") }
                                count(4)
                            }
                            addMethod {
                                modifiers(Modifier.PUBLIC or Modifier.FINAL)
                                returnType("android.content.Intent")
                                name("f")
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
                        matcher { usingStrings("nogms_access_token") }
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
                    oauthHelper = bridge.findSingle(cl, "oauthHelperClass", filterInner = false) {
                        matcher { usingStrings("org.swiftapps.swiftbackup:/oauth") }
                    }
                }

                if (authRequestBuilder == null) {
                    authRequestBuilder = bridge.findSingle(cl, "authRequestBuilderClass", filterInner = false) {
                        matcher { usingStrings("client ID cannot be null or empty") }
                    }
                }

                if (appBackup == null) {
                    appBackup = bridge.findSingle(cl, "appBackupClass", filterInner = false) {
                        matcher { usingStrings("apkBackupDate", "dataBackupDate") }
                    }
                }

                if (appMetadataXml == null) {
                    appMetadataXml = bridge.findSingle(cl, "appMetadataXmlClass", filterInner = false) {
                        matcher { usingStrings("dateBackupUpdated", "minSBVersionCodeRequired") }
                    }
                }

                if (fireSynchronizer == null) {
                    fireSynchronizer = bridge.findSingle(cl, "fireSynchronizerClass", filterInner = true) {
                        matcher { usingStrings("FireSynchronizer") }
                    }
                }

                if (fireSynchronizerSuccess == null && fireSynchronizer != null) {
                    val readMethod = fireSynchronizer?.declaredMethods?.firstOrNull {
                        it.parameterCount == 2 && it.parameterTypes[1] == Boolean::class.javaPrimitiveType
                    }
                    val resultBaseClass = readMethod?.returnType
                    if (resultBaseClass != null) {
                        fireSynchronizerSuccess = bridge.findSingle(cl, "fireSynchronizerSuccessClass", filterInner = false) {
                            matcher {
                                superClass(resultBaseClass.name)
                            }
                        }
                    }
                }

                if (firebaseWatcher == null) {
                    firebaseWatcher = bridge.findSingle(cl, "firebaseWatcherClass", filterInner = true) {
                        matcher { usingStrings("FCW", ".info/connected") }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(Consts.TAG, "DexKit search encountered an error", t)
        }

        if (clientId == null || homeVm == null) {
            Log.w(Consts.TAG, "Couldn't fully hook Swift Backup.")
        }

        return ResolvedTargets(clientId, v, cloudGms, homeVm, authUser, anonUser, oauthHelper, authRequestBuilder, appBackup, appMetadataXml, fireSynchronizer, firebaseWatcher, fireSynchronizerSuccess)
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
}
