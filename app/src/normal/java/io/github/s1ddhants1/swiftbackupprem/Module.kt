package io.github.s1ddhants1.swiftbackupprem

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.annotation.Keep
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.s1ddhants1.swiftbackupprem.hook.*
import io.github.s1ddhants1.swiftbackupprem.util.BackupCrypto
import io.github.s1ddhants1.swiftbackupprem.util.LSPatchHelper
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import java.lang.reflect.Executable
import java.util.concurrent.ConcurrentHashMap

@Keep
class Module : XposedModule(), HookContext {
    private val hookHandles = ConcurrentHashMap<String, XposedInterface.HookHandle>()

    fun rememberHook(id: String?, handle: XposedInterface.HookHandle) {
        if (id != null) hookHandles[id] = handle
    }

    override fun hookTracked(
        executable: Executable,
        idPrefix: String,
        priority: Int,
        deoptimize: Boolean
    ): HookBuilder {
        if (deoptimize) {
            attempt("deoptimize ${executable.declaringClass.simpleName}#${executable.name}", silent = true) {
                deoptimize(executable)
            }
        }

        val params = executable.parameterTypes.joinToString(",") { it.name }
        val sig = "${executable.declaringClass.name}#${executable.name}($params)"
        var hookId = "${idPrefix.replace(Regex("[^A-Za-z0-9_.#-]"), "_")}:${sig.hashCode().toUInt().toString(16)}"

        val builder = hook(executable)
            .setPriority(priority)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)

        if (apiVersion >= XposedInterface.API_102) {
            builder.setId(hookId)
        }

        return object : HookBuilder {
            override fun setPriority(priority: Int) = apply { builder.setPriority(priority) }
            override fun setExceptionMode(mode: ExceptionMode) = apply {
                val libMode = when (mode) {
                    ExceptionMode.DEFAULT -> XposedInterface.ExceptionMode.DEFAULT
                    ExceptionMode.PROTECTIVE -> XposedInterface.ExceptionMode.PROTECTIVE
                    ExceptionMode.PASSTHROUGH -> XposedInterface.ExceptionMode.PASSTHROUGH
                }
                builder.setExceptionMode(libMode)
            }
            override fun setId(id: String?) = apply {
                if (id != null) hookId = id
                if (apiVersion >= XposedInterface.API_102) builder.setId(id)
            }
            override fun intercept(hooker: (Chain) -> Any?): HookHandle {
                val handle = builder.intercept { chain ->
                    val wrappedChain = object : Chain {
                        override val thisObject: Any? get() = chain.thisObject
                        override val args: List<Any?> get() = chain.args.toList()
                        override fun getArg(index: Int): Any? = chain.args.getOrNull(index)
                        override fun proceed(): Any? = chain.proceed()
                        override fun proceed(args: Array<Any?>): Any? = chain.proceed(args)
                    }
                    hooker(wrappedChain)
                }
                rememberHook(hookId, handle)
                return HookHandle {
                    try {
                        handle.unhook()
                    } catch (_: Throwable) {}
                }
            }
        }
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
            priority = PRIORITY_HIGHEST,
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
        val hasRemotePrefs = remotePrefs != null && remotePrefs.all.isNotEmpty()
        val isIntegrated = LSPatchHelper.isIntegratedMode(ctx, remotePrefsAvailable = hasRemotePrefs)
        Log.i(Consts.TAG, "applyHooks: isIntegrated=$isIntegrated, hasRemotePrefs=$hasRemotePrefs")
        val prefs = PreferencesManager(remotePrefs, isDynamic = true)
        if (isIntegrated || remotePrefs == null || remotePrefs.all.isEmpty()) {
            prefs.loadFromFallbackStorage(ctx)
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
}
