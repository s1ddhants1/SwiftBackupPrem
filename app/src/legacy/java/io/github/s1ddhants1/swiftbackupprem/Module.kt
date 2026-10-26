package io.github.s1ddhants1.swiftbackupprem

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.Keep
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.s1ddhants1.swiftbackupprem.hook.*
import io.github.s1ddhants1.swiftbackupprem.util.BackupCrypto
import io.github.s1ddhants1.swiftbackupprem.util.LSPatchHelper
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.util.Collections
import java.util.WeakHashMap

@Keep
class Module : IXposedHookLoadPackage, HookContext {
    private val activeActivities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    private val forceStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Consts.actionForceStop) {
                terminateTargetProcess()
            }
        }
    }

    private fun terminateTargetProcess() {
        attempt("terminate target process") {
            val mainLooper = Looper.getMainLooper()
            if (Looper.myLooper() == mainLooper) {
                activeActivities.forEach { attempt("finish activity", silent = true) { it.finishAndRemoveTask() } }
                activeActivities.clear()
            } else {
                Handler(mainLooper).post {
                    activeActivities.forEach { attempt("finish activity", silent = true) { it.finishAndRemoveTask() } }
                    activeActivities.clear()
                }
                Thread.sleep(50)
            }
        }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == BuildConfig.APPLICATION_ID) {
            attempt("hook isModuleActive") {
                val appClass = lpparam.classLoader.loadClass("io.github.s1ddhants1.swiftbackupprem.App")
                val isModuleActiveMethod = appClass.getDeclaredMethod("isModuleActive")
                XposedBridge.hookMethod(isModuleActiveMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                })
            }
            return
        }

        if (lpparam.packageName != Consts.packageName) return

        attempt("load native library") {
            System.loadLibrary("nativelib")
        }

        val xPrefs = XSharedPreferences(BuildConfig.APPLICATION_ID)
        try {
            @Suppress("DEPRECATION")
            xPrefs.makeWorldReadable()
            xPrefs.reload()
        } catch (_: Throwable) {}

        Log.d(Consts.TAG, "xPrefs file: ${xPrefs.file?.absolutePath}, exists: ${xPrefs.file?.exists()}, canRead: ${xPrefs.file?.canRead()}, keys: ${xPrefs.all?.keys}")

        val cl = lpparam.classLoader
        ExitProtectionHook.applyEarly(this, cl)

        val swiftAppClass = attempt("load SwiftApp class") { cl.loadClass("org.swiftapps.swiftbackup.SwiftApp") } ?: return
        val onCreateMethod = attempt("find SwiftApp.onCreate") { swiftAppClass.getDeclaredMethod("onCreate") } ?: return

        XposedBridge.hookMethod(onCreateMethod, object : XC_MethodHook(PRIORITY_HIGHEST) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                xPrefs.reload()
                Log.d(Consts.TAG, "SwiftApp.onCreate beforeHookedMethod: xPrefs keys=${xPrefs.all?.keys}, custom_firebase_app=${xPrefs.getBoolean("custom_firebase_app", false)}")
                val ctx = param.thisObject as? Context ?: return
                val hookData = applyHooks(ctx, cl, lpparam.appInfo?.sourceDir ?: "", xPrefs, param.thisObject)
                FirebaseInitHook.applyStaticClientId(hookData.first, hookData.second)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                xPrefs.reload()
                val ctx = param.thisObject as? Context ?: return
                val prefs = PreferencesManager(xPrefs, isDynamic = true)
                prefs.loadFromFallbackStorage(ctx)
                var targets = ResolvedTargets()
                attempt("find obfuscated classes with DexKit for post-create clientId injection") {
                    targets = TargetClassResolver.resolve(ctx, cl, lpparam.appInfo?.sourceDir ?: "")
                }
                FirebaseInitHook.applyStaticClientId(targets, prefs)
            }
        })
    }

    private fun applyHooks(
        ctx: Context,
        cl: ClassLoader,
        sourceDir: String,
        xPrefs: XSharedPreferences,
        swiftAppInstance: Any? = null
    ): Pair<ResolvedTargets, PreferencesManager> {
        val prefs = PreferencesManager(xPrefs, isDynamic = true)
        val hasPrefs = xPrefs.all.isNotEmpty()
        val isIntegrated = LSPatchHelper.isIntegratedMode(ctx, remotePrefsAvailable = hasPrefs)
        if (isIntegrated || !hasPrefs) {
            prefs.loadFromFallbackStorage(ctx)
        }

        val app = ctx.applicationContext as? Application ?: ctx as? Application
        app?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                activeActivities.add(activity)
            }

            override fun onActivityDestroyed(activity: Activity) {
                activeActivities.remove(activity)
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })

        attempt("register force stop receiver", silent = true) {
            val filter = IntentFilter(Consts.actionForceStop)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(forceStopReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(forceStopReceiver, filter)
            }
        }

        var targets = ResolvedTargets()
        attempt("find obfuscated classes with DexKit") {
            targets = TargetClassResolver.resolve(ctx, cl, sourceDir)
        }

        ExitProtectionHook.apply(this, ctx, cl, targets, prefs)

        if (LSPatchHelper.isLSPatched(ctx)) {
            RootServiceFixHook.apply(this, ctx, cl, targets, prefs)
            ShizukuServiceFixHook.apply(this, ctx, cl, targets, prefs)
        }

        FirebaseInitHook.apply(this, ctx, cl, targets, prefs)
        FirebaseInitHook.applyStaticClientId(targets, prefs)
        PremiumFeatureHook.apply(this, ctx, cl, targets, prefs)
        if (swiftAppInstance != null) {
            PremiumFeatureHook.hookSwiftAppPremium(this, swiftAppInstance, prefs)
        }
        AuthBypassHook.apply(this, ctx, cl, targets, prefs)
        GoogleDriveScopeHook.apply(this, ctx, cl, targets, prefs)
        TelemetrySuppressionHook.apply(this, ctx, cl, targets, prefs)
        BackupRebuilderHook.apply(this, ctx, cl, targets, prefs)
        CloudDiscoveryHook.apply(this, ctx, cl, targets, prefs)
        LocalCloudUnlockHook.apply(this, ctx, cl, targets, prefs)
        if (isIntegrated) {
            InAppSettingsHook.apply(this, ctx, cl, targets, prefs)
        }

        attempt("export detected UIDs and auth state to storage", silent = true) {
            val uids = BackupCrypto.resolveCandidateUids(ctx, cl, targets, prefs)
            BackupCrypto.syncDetectedUids(ctx, uids)

            val exportDirs = listOf(
                java.io.File("/storage/emulated/0/SwiftBackup"),
                ctx.getExternalFilesDir(null),
                java.io.File("/storage/emulated/0/Android/data/${Consts.packageName}/files")
            )
            val sbPrefs = ctx.getSharedPreferences("org.swiftapps.swiftbackup_preferences", Context.MODE_PRIVATE)
            val authState = sbPrefs.getString("nogms_auth_state", null)
            if (!authState.isNullOrBlank()) {
                for (dir in exportDirs) {
                    if (dir != null && dir.exists() && dir.isDirectory) {
                        java.io.File(dir, ".sbp_auth_state").writeText(authState)
                    }
                }
            }
        }

        return Pair(targets, prefs)
    }

    override fun hookTracked(
        executable: Executable,
        idPrefix: String,
        priority: Int,
        deoptimize: Boolean
    ): HookBuilder {
        return object : HookBuilder {
            private var currentPriority = priority

            override fun setPriority(priority: Int) = apply { this.currentPriority = priority }
            override fun setExceptionMode(mode: ExceptionMode) = this
            override fun setId(id: String?) = this

            override fun intercept(hooker: (Chain) -> Any?): HookHandle {
                val isCtor = executable is Constructor<*>
                var unhookObj: XC_MethodHook.Unhook? = null

                val methodHook = object : XC_MethodHook(currentPriority) {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        var proceedCalled = false
                        var modifiedArgs: Array<Any?>? = null

                        val beforeChain = object : Chain {
                            override val thisObject: Any? get() = param.thisObject
                            override val args: List<Any?> get() = (modifiedArgs ?: param.args).toList()
                            override fun getArg(index: Int): Any? = (modifiedArgs ?: param.args).getOrNull(index)

                            override fun proceed(): Any? {
                                proceedCalled = true
                                return null
                            }

                            override fun proceed(args: Array<Any?>): Any? {
                                proceedCalled = true
                                modifiedArgs = args
                                for (i in args.indices) {
                                    if (i < param.args.size) {
                                        param.args[i] = args[i]
                                    }
                                }
                                return null
                            }
                        }

                        try {
                            val res = hooker(beforeChain)
                            val currentModifiedArgs = modifiedArgs
                            if (currentModifiedArgs != null) {
                                for (i in currentModifiedArgs.indices) {
                                    if (i < param.args.size) {
                                        param.args[i] = currentModifiedArgs[i]
                                    }
                                }
                            }
                            if (!proceedCalled && !isCtor) {
                                param.result = res
                            }
                        } catch (t: Throwable) {
                            Log.e(Consts.TAG, "Error in legacy beforeHookedMethod for $executable ($idPrefix)", t)
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (isCtor || param.hasThrowable()) return

                        val afterChain = object : Chain {
                            override val thisObject: Any? get() = param.thisObject
                            override val args: List<Any?> get() = param.args.toList()
                            override fun getArg(index: Int): Any? = param.args.getOrNull(index)

                            override fun proceed(): Any? = param.result

                            override fun proceed(args: Array<Any?>): Any? = param.result
                        }

                        try {
                            val res = hooker(afterChain)
                            if (res != null || param.result == null) {
                                param.result = res
                            }
                        } catch (t: Throwable) {
                            Log.e(Consts.TAG, "Error in legacy afterHookedMethod for $executable ($idPrefix)", t)
                        }
                    }
                }

                try {
                    unhookObj = XposedBridge.hookMethod(executable as Member, methodHook)
                } catch (t: Throwable) {
                    Log.e(Consts.TAG, "Failed to hook method with legacy Xposed: $executable ($idPrefix)", t)
                }

                return HookHandle {
                    try {
                        unhookObj?.unhook()
                    } catch (_: Throwable) {}
                }
            }
        }
    }
}
