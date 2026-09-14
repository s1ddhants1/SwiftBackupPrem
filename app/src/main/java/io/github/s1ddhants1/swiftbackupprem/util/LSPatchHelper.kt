package io.github.s1ddhants1.swiftbackupprem.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import io.github.s1ddhants1.swiftbackupprem.Consts
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import io.github.libxposed.service.XposedService
import io.github.s1ddhants1.swiftbackupprem.R
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import org.json.JSONObject
import org.lsposed.lspatch.IXposedServicePull

object LSPatchHelper {
    const val ACTION_REQUEST_PUSH = "org.lsposed.lspatch.action.REQUEST_PUSH"

    @Volatile
    private var lastRequestTime = 0L

    fun requestServicePush(context: Context) {
        attempt("request LSPatch service push", silent = true) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastRequestTime < 3000L) {
                return@attempt
            }
            lastRequestTime = now

            val appContext = context.applicationContext ?: context
            val intent = Intent(ACTION_REQUEST_PUSH)
            val resolveInfos = appContext.packageManager.queryIntentServices(intent, 0)
            if (resolveInfos.isEmpty()) {
                Log.d(Consts.TAG, "No LSPatch manager service found for $ACTION_REQUEST_PUSH")
                return@attempt
            }
            for (info in resolveInfos) {
                val serviceIntent = Intent(intent).apply {
                    component = ComponentName(info.serviceInfo.packageName, info.serviceInfo.name)
                }
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        try {
                            val pullService = IXposedServicePull.Stub.asInterface(service)
                            val accepted = pullService?.requestPush() == true
                            Log.i(Consts.TAG, "LSPatch service push requested from $name: accepted=$accepted")
                        } catch (t: Throwable) {
                            Log.w(Consts.TAG, "Failed to call requestPush on $name", t)
                        } finally {
                            try {
                                appContext.unbindService(this)
                            } catch (_: Throwable) {}
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {}
                }
                try {
                    appContext.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
                } catch (t: Throwable) {
                    Log.w(Consts.TAG, "Failed to bind to LSPatch pull service: ${info.serviceInfo.packageName}", t)
                }
            }
        }
    }

    data class TargetStatus(
        val isInstalled: Boolean,
        val isPatched: Boolean,
        val isModuleEmbedded: Boolean,
        val useManager: Boolean? = null
    )

    data class BannerEvaluation(
        val isConnected: Boolean,
        val isInjectable: Boolean,
        val frameworkName: String,
        val frameworkVersion: String,
        val titleRes: Int,
        val titleArgs: List<String> = emptyList(),
        val descRes: Int,
        val descArgs: List<String> = emptyList()
    )

    fun inspectTargetApp(context: Context): TargetStatus {
        return attempt("inspect target app", silent = true) {
            val pm = context.packageManager
            val appInfo = attempt("get target app info", silent = true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(
                        Consts.packageName,
                        PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(Consts.packageName, PackageManager.GET_META_DATA)
                }
            } ?: return@attempt TargetStatus(isInstalled = false, isPatched = false, isModuleEmbedded = false)

            val hasMeta = appInfo.metaData?.containsKey("lspatch") == true
            val hasFactory = appInfo.appComponentFactory?.contains("lspatch", ignoreCase = true) == true
            val sourceDir = appInfo.sourceDir

            var hasLspAsset = false
            var isEmbedded = false
            var useManager: Boolean? = null

            if (sourceDir != null) {
                attempt("read target apk assets", silent = true) {
                    ZipFile(sourceDir).use { zip ->
                        val configEntry = zip.getEntry("assets/lspatch/config.json")
                        val originEntry = zip.getEntry("assets/lspatch/origin.apk")
                        val metaEntry = zip.getEntry("assets/lspatch/metaloader.dex")
                        hasLspAsset = configEntry != null || originEntry != null || metaEntry != null

                        val moduleEntry = zip.getEntry("assets/lspatch/modules/io.github.s1ddhants1.swiftbackupprem.apk")
                        isEmbedded = moduleEntry != null

                        if (configEntry != null) {
                            try {
                                val configText = zip.getInputStream(configEntry).bufferedReader().readText()
                                val json = JSONObject(configText)
                                useManager = json.optBoolean("useManager", true)
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }

            if (useManager == null && hasMeta) {
                attempt("read useManager from lspatch metadata", silent = true) {
                    val b64 = appInfo.metaData?.getString("lspatch")
                    if (!b64.isNullOrBlank()) {
                        val jsonStr = String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8)
                        val json = JSONObject(jsonStr)
                        useManager = json.optBoolean("useManager", true)
                    }
                }
            }

            val isPatched = hasMeta || hasFactory || hasLspAsset
            TargetStatus(
                isInstalled = true,
                isPatched = isPatched,
                isModuleEmbedded = isEmbedded,
                useManager = useManager
            )
        } ?: TargetStatus(isInstalled = false, isPatched = false, isModuleEmbedded = false)
    }

    fun evaluateFrameworkStatus(
        context: Context,
        service: XposedService?
    ): BannerEvaluation = evaluateFrameworkStatus(
        service = service,
        targetStatus = inspectTargetApp(context)
    )

    fun evaluateFrameworkStatus(
        service: XposedService?,
        targetStatus: TargetStatus
    ): BannerEvaluation = evaluateFrameworkStatus(
        frameworkName = attempt("get frameworkName", silent = true) { service?.frameworkName },
        frameworkVersion = attempt("get frameworkVersion", silent = true) { service?.frameworkVersion },
        scope = attempt("get service scope", silent = true) { service?.scope },
        isServiceBound = service != null,
        targetStatus = targetStatus
    )

    fun evaluateFrameworkStatus(
        frameworkName: String?,
        frameworkVersion: String?,
        scope: List<String>?,
        isServiceBound: Boolean,
        targetStatus: TargetStatus
    ): BannerEvaluation {
        if (isServiceBound) {
            val name = frameworkName ?: "Xposed"
            val version = frameworkVersion ?: ""
            val isLSPatch = name.contains("LSPatch", ignoreCase = true)

            if (isLSPatch) {
                if (!targetStatus.isInstalled) {
                    return BannerEvaluation(
                        isConnected = true,
                        isInjectable = false,
                        frameworkName = name,
                        frameworkVersion = version,
                        titleRes = R.string.framework_sb_not_installed_title,
                        descRes = R.string.framework_sb_not_installed_desc
                    )
                }
                if (!targetStatus.isPatched) {
                    return BannerEvaluation(
                        isConnected = true,
                        isInjectable = false,
                        frameworkName = name,
                        frameworkVersion = version,
                        titleRes = R.string.framework_sb_not_patched_title,
                        descRes = R.string.framework_sb_not_patched_desc
                    )
                }

                val currentScope = scope ?: emptyList()
                val isInScope = currentScope.contains(Consts.packageName)
                val isInjectable = if (targetStatus.useManager == false) {
                    targetStatus.isModuleEmbedded || isInScope
                } else {
                    isInScope
                }

                if (!isInjectable) {
                    return BannerEvaluation(
                        isConnected = true,
                        isInjectable = false,
                        frameworkName = name,
                        frameworkVersion = version,
                        titleRes = R.string.framework_sb_not_in_scope_title,
                        descRes = R.string.framework_sb_not_in_scope_desc
                    )
                }

                return BannerEvaluation(
                    isConnected = true,
                    isInjectable = true,
                    frameworkName = name,
                    frameworkVersion = version,
                    titleRes = R.string.framework_active_title_dynamic,
                    titleArgs = listOf(name),
                    descRes = R.string.framework_active_desc,
                    descArgs = listOf(name, version)
                )
            } else {
                val currentScope = scope ?: emptyList()
                val isInScope = currentScope.isEmpty() || currentScope.contains(Consts.packageName)

                if (!isInScope) {
                    return BannerEvaluation(
                        isConnected = true,
                        isInjectable = false,
                        frameworkName = name,
                        frameworkVersion = version,
                        titleRes = R.string.framework_sb_not_in_scope_title,
                        descRes = R.string.framework_sb_not_in_scope_desc
                    )
                }

                return BannerEvaluation(
                    isConnected = true,
                    isInjectable = true,
                    frameworkName = name,
                    frameworkVersion = version,
                    titleRes = R.string.framework_active_title_dynamic,
                    titleArgs = listOf(name),
                    descRes = R.string.framework_active_desc,
                    descArgs = listOf(name, version)
                )
            }
        } else {
            if (targetStatus.isInstalled && targetStatus.isPatched && targetStatus.isModuleEmbedded) {
                return BannerEvaluation(
                    isConnected = true,
                    isInjectable = true,
                    frameworkName = "LSPatch",
                    frameworkVersion = "(Embedded)",
                    titleRes = R.string.framework_lspatch_embedded_active_title,
                    descRes = R.string.framework_lspatch_embedded_active_desc
                )
            }

            return BannerEvaluation(
                isConnected = false,
                isInjectable = false,
                frameworkName = "",
                frameworkVersion = "",
                titleRes = R.string.framework_inactive_title,
                descRes = R.string.framework_inactive_desc
            )
        }
    }
}
