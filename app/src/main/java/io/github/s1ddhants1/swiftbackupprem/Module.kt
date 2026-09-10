package io.github.s1ddhants1.swiftbackupprem

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.annotation.Keep
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.s1ddhants1.swiftbackupprem.hook.*
import io.github.s1ddhants1.swiftbackupprem.hook.experimental.BackupRebuilderHook
import io.github.s1ddhants1.swiftbackupprem.hook.experimental.CloudDiscoveryHook
import io.github.s1ddhants1.swiftbackupprem.hook.experimental.GoogleDriveScopeHook
import io.github.s1ddhants1.swiftbackupprem.util.BackupCrypto
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import java.util.concurrent.ConcurrentHashMap

@Keep
class Module : XposedModule() {
    private val hookHandles = ConcurrentHashMap<String, XposedInterface.HookHandle>()

    fun rememberHook(id: String?, handle: XposedInterface.HookHandle) {
        if (id != null) hookHandles[id] = handle
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        if (!param.isFirstPackage || param.packageName != Consts.packageName) {
            if (apiVersion >= XposedInterface.API_102) {
                attempt("detach non-target package", silent = true) { detach() }
            }
            return
        }

        attempt("load nativelib native library") { System.loadLibrary("nativelib") }

        val cl = param.classLoader
        ExitProtectionHook.applyEarly(this, cl)

        val swiftAppClass = attempt("load SwiftApp class") { cl.loadClass("org.swiftapps.swiftbackup.SwiftApp") } ?: return
        val onCreateMethod = attempt("find SwiftApp.onCreate") { swiftAppClass.getDeclaredMethod("onCreate") } ?: return

        hookTracked(
            onCreateMethod,
            idPrefix = "swift-app-on-create",
            priority = XposedInterface.PRIORITY_HIGHEST,
            deoptimize = true
        ).intercept { chain ->
            val ctx = chain.thisObject as? Context
            var deferredHookData: Pair<ResolvedTargets, PreferencesManager>? = null
            if (ctx != null) {
                deferredHookData = applyHooks(ctx, cl, param.applicationInfo.sourceDir, chain.thisObject)
            }
            chain.proceed().also {
                deferredHookData?.let { (targets, prefs) ->
                    FirebaseInitHook.applyStaticClientId(targets, prefs)
                }
            }
        }
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        Log.i(Consts.TAG, "Preparing old module generation for hot reload...")
        
        val state = Bundle().apply {
            putLong("hot_reload_timestamp", System.currentTimeMillis())
        }
        param.setSavedInstanceState(state)

        BackupRebuilderHook.shutdown()
        CloudDiscoveryHook.shutdown()
        LocalCloudUnlockHook.shutdown()
        ExitProtectionHook.reset()
        hookHandles.clear()

        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        Log.i(Consts.TAG, "Hot reloaded SwiftBackupPrem in process ${param.processName}!")

        for (oldHandle in param.oldHookHandles) {
            try {
                oldHandle.unhook()
            } catch (_: Throwable) {}
        }
        hookHandles.clear()

        attempt("load nativelib native library on hot reload") { System.loadLibrary("nativelib") }

        val app = attempt("get current Application", silent = true) {
            val atClass = Class.forName("android.app.ActivityThread")
            atClass.getDeclaredMethod("currentApplication").invoke(null) as? android.app.Application
        }
        if (app != null && app.packageName == Consts.packageName) {
            val cl = app.classLoader
            ExitProtectionHook.applyEarly(this, cl)
            applyHooks(app, cl, app.applicationInfo.sourceDir, app)?.let { (targets, prefs) ->
                FirebaseInitHook.applyStaticClientId(targets, prefs)
            }
        }
    }

    private fun applyHooks(ctx: Context, cl: ClassLoader, sourceDir: String, swiftAppInstance: Any? = null): Pair<ResolvedTargets, PreferencesManager>? {
        val remotePrefs = attempt("get remote preferences") { getRemotePreferences(Consts.PREFS_SETTINGS) }
        val prefs = PreferencesManager(remotePrefs, isDynamic = true)

        var targets = ResolvedTargets()
        attempt("find obfuscated classes with DexKit") {
            targets = TargetClassResolver.resolve(ctx, cl, sourceDir)
        }

        ExitProtectionHook.apply(this, ctx, cl, targets, prefs)
        FirebaseInitHook.apply(this, ctx, cl, targets, prefs)
        PremiumFeatureHook.apply(this, ctx, cl, targets, prefs)
        if (swiftAppInstance != null) {
            PremiumFeatureHook.hookSwiftAppPremium(this, swiftAppInstance, prefs.enablePremium)
        }
        AuthBypassHook.apply(this, ctx, cl, targets, prefs)
        GoogleDriveScopeHook.apply(this, ctx, cl, targets, prefs)
        TelemetrySuppressionHook.apply(this, ctx, cl, targets, prefs)
        BackupRebuilderHook.apply(this, ctx, cl, targets, prefs)
        CloudDiscoveryHook.apply(this, ctx, cl, targets, prefs)
        LocalCloudUnlockHook.apply(this, ctx, cl, targets, prefs)

        if (swiftAppInstance != null) {
            PremiumFeatureHook.hookSwiftAppPremium(this, swiftAppInstance, prefs.enablePremium)
        }

        // Export detected UIDs and auth state to shared storage for Manager app / Migrator UI
        attempt("export detected UIDs and auth state to storage", silent = true) {
            val uids = BackupCrypto.resolveCandidateUids(ctx, cl, targets)
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
}

