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
        val currentFramework = io.github.s1ddhants1.swiftbackupprem.FrameworkHelper.frameworkName
        if (currentFramework.isNotEmpty() && !currentFramework.contains("LSPatch", ignoreCase = true)) {
            Log.d(Consts.TAG, "Active non-LSPatch framework detected ($currentFramework); skipping LSPatch push request")
            return
        }
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
        val descArgs: List<String> = emptyList(),
        val isIntegrated: Boolean = false
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
            val hasFactory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                appInfo.appComponentFactory?.contains("lspatch", ignoreCase = true) == true
            } else {
                false
            }
            val sourceDir = appInfo.sourceDir

            var hasLspAsset = false
            var isEmbedded = false
            var useManager: Boolean? = null

            val apkPaths = mutableListOf<String>()
            if (sourceDir != null) apkPaths.add(sourceDir)
            appInfo.splitSourceDirs?.let { apkPaths.addAll(it) }

            for (apkPath in apkPaths) {
                attempt("read target apk assets from $apkPath", silent = true) {
                    try {
                        ZipFile(apkPath).use { zip ->
                            val configEntry = zip.getEntry("assets/lspatch/config.json")
                            val originEntry = zip.getEntry("assets/lspatch/origin.apk")
                            val metaEntry = zip.getEntry("assets/lspatch/metaloader.dex")
                            if (configEntry != null || originEntry != null || metaEntry != null) {
                                hasLspAsset = true
                            }

                            val moduleEntry = zip.getEntry("assets/lspatch/modules/io.github.s1ddhants1.swiftbackupprem.apk")
                                ?: zip.entries().asSequence().firstOrNull {
                                    it.name.startsWith("assets/lspatch/modules/") &&
                                        it.name.contains("swiftbackupprem", ignoreCase = true)
                                }
                            if (moduleEntry != null) {
                                isEmbedded = true
                            }

                            if (configEntry != null && useManager == null) {
                                try {
                                    val configText = zip.getInputStream(configEntry).bufferedReader().readText()
                                    val json = JSONObject(configText)
                                    useManager = json.optBoolean("useManager", true)
                                } catch (_: Throwable) {}
                            }
                        }
                    } catch (_: Throwable) {}
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

    fun isLSPatched(context: Context): Boolean {
        return attempt("check if running under LSPatch", silent = true) {
            val appInfo = context.applicationInfo
            val hasMeta = appInfo.metaData?.containsKey("lspatch") == true
            val hasFactory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                appInfo.appComponentFactory?.contains("lspatch", ignoreCase = true) == true
            } else false
            if (hasMeta || hasFactory) return@attempt true
            val pm = context.packageManager
            val installer = attempt("get installer package", silent = true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    pm.getInstallSourceInfo(context.packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstallerPackageName(context.packageName)
                }
            }
            if (installer?.contains("lspatch", ignoreCase = true) == true) return@attempt true
            val cacheDir = java.io.File(context.cacheDir, "lspatch/origin")
            cacheDir.isDirectory
        } ?: false
    }

    fun resolveOriginApkPath(classLoader: ClassLoader): String? {
        return attempt("resolve origin APK from classloader dex elements", silent = true) {
            var cl: ClassLoader? = classLoader
            while (cl != null) {
                val currentCl = cl
                val pathList = attempt("get pathList from ${currentCl.javaClass.name}", silent = true) {
                    val field = currentCl.javaClass.superclass?.getDeclaredField("pathList")
                        ?: currentCl.javaClass.getDeclaredField("pathList")
                    field.isAccessible = true
                    field.get(currentCl)
                }
                if (pathList != null) {
                    val dexElements = attempt("get dexElements", silent = true) {
                        val field = pathList.javaClass.getDeclaredField("dexElements")
                        field.isAccessible = true
                        field.get(pathList) as? Array<*>
                    }
                    if (dexElements != null) {
                        for (element in dexElements) {
                            if (element == null) continue
                            val path = attempt("get element path", silent = true) {
                                val pathField = element.javaClass.getDeclaredField("path")
                                pathField.isAccessible = true
                                pathField.get(element) as? java.io.File
                            } ?: continue

                            val absPath = path.absolutePath
                            if (absPath.contains("origin", ignoreCase = true) && absPath.endsWith(".apk")) {
                                return@attempt absPath
                            }
                        }
                    }
                }
                cl = cl.parent
            }
            val candidateDirs = listOf(
                "/data/data/${Consts.packageName}/cache/lspatch/origin",
                "/data/user/0/${Consts.packageName}/cache/lspatch/origin"
            )
            for (dirPath in candidateDirs) {
                val dir = java.io.File(dirPath)
                if (dir.isDirectory) {
                    val apk = dir.listFiles()?.firstOrNull { it.extension == "apk" }
                    if (apk != null) return@attempt apk.absolutePath
                }
            }

            null
        }
    }

    fun isIntegratedMode(
        context: Context? = null,
        remotePrefsAvailable: Boolean = false,
        targetStatusProvider: () -> TargetStatus = {
            if (context != null) inspectTargetApp(context) else TargetStatus(isInstalled = false, isPatched = false, isModuleEmbedded = false)
        }
    ): Boolean {
        if (context != null) {
            val metaResult = attempt("check lspatch metadata", silent = true) {
                val pm = context.packageManager
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(
                        context.packageName,
                        PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                }
                val meta = appInfo.metaData
                val b64 = meta?.getString("lspatch")
                if (!b64.isNullOrBlank()) {
                    val jsonStr = String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8)
                    val json = JSONObject(jsonStr)
                    if (json.has("useManager")) {
                        return@attempt !json.optBoolean("useManager", true)
                    }
                }
                null
            }
            if (metaResult != null) return metaResult
        }

        val status = targetStatusProvider()
        if (status.useManager != null) {
            return !status.useManager
        }
        if (status.isModuleEmbedded) {
            return true
        }

        return !remotePrefsAvailable
    }

    fun evaluateFrameworkStatus(
        context: Context,
        frameworkName: String?,
        frameworkVersion: String?,
        scope: List<String>?,
        isServiceBound: Boolean
    ): BannerEvaluation = evaluateFrameworkStatus(
        frameworkName = frameworkName,
        frameworkVersion = frameworkVersion,
        scope = scope,
        isServiceBound = isServiceBound,
        targetStatus = inspectTargetApp(context)
    )

    fun evaluateFrameworkStatus(
        context: Context,
        isModuleActive: Boolean = io.github.s1ddhants1.swiftbackupprem.App.isModuleActive()
    ): BannerEvaluation = evaluateFrameworkStatus(
        isModuleActive = isModuleActive,
        targetStatus = inspectTargetApp(context)
    )

    fun evaluateFrameworkStatus(
        isModuleActive: Boolean,
        targetStatus: TargetStatus
    ): BannerEvaluation {
        if (targetStatus.isInstalled && targetStatus.isPatched && (targetStatus.isModuleEmbedded || targetStatus.useManager == false)) {
            return BannerEvaluation(
                isConnected = true,
                isInjectable = true,
                frameworkName = "LSPatch",
                frameworkVersion = "(Embedded)",
                titleRes = R.string.framework_lspatch_embedded_active_title,
                descRes = R.string.framework_lspatch_embedded_active_desc,
                isIntegrated = true
            )
        }

        if (isModuleActive) {
            return BannerEvaluation(
                isConnected = true,
                isInjectable = true,
                frameworkName = "Xposed",
                frameworkVersion = "(Legacy)",
                titleRes = R.string.framework_active_title_dynamic,
                titleArgs = listOf("Xposed"),
                descRes = R.string.framework_active_desc,
                descArgs = listOf("Xposed", "(Legacy)"),
                isIntegrated = false
            )
        }

        if (!targetStatus.isInstalled) {
            return BannerEvaluation(
                isConnected = false,
                isInjectable = false,
                frameworkName = "",
                frameworkVersion = "",
                titleRes = R.string.framework_sb_not_installed_title,
                descRes = R.string.framework_sb_not_installed_desc,
                isIntegrated = false
            )
        }

        return BannerEvaluation(
            isConnected = false,
            isInjectable = false,
            frameworkName = "",
            frameworkVersion = "",
            titleRes = R.string.framework_inactive_title,
            descRes = R.string.framework_inactive_desc,
            isIntegrated = false
        )
    }

    fun evaluateFrameworkStatus(
        frameworkName: String?,
        frameworkVersion: String?,
        scope: List<String>?,
        isServiceBound: Boolean,
        targetStatus: TargetStatus
    ): BannerEvaluation {
        if (targetStatus.isInstalled && targetStatus.isPatched && (targetStatus.isModuleEmbedded || targetStatus.useManager == false)) {
            return BannerEvaluation(
                isConnected = true,
                isInjectable = true,
                frameworkName = "LSPatch",
                frameworkVersion = "(Embedded)",
                titleRes = R.string.framework_lspatch_embedded_active_title,
                descRes = R.string.framework_lspatch_embedded_active_desc,
                isIntegrated = true
            )
        }

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
                        descRes = R.string.framework_sb_not_installed_desc,
                        isIntegrated = false
                    )
                }
                if (!targetStatus.isPatched) {
                    return BannerEvaluation(
                        isConnected = false,
                        isInjectable = false,
                        frameworkName = name,
                        frameworkVersion = version,
                        titleRes = R.string.framework_inactive_title,
                        descRes = R.string.framework_inactive_desc,
                        isIntegrated = false
                    )
                }

                val currentScope = scope ?: emptyList()
                val isInScope = currentScope.contains(Consts.packageName)

                if (!isInScope) {
                    return BannerEvaluation(
                        isConnected = true,
                        isInjectable = false,
                        frameworkName = name,
                        frameworkVersion = version,
                        titleRes = R.string.framework_sb_not_in_scope_title,
                        descRes = R.string.framework_sb_not_in_scope_desc,
                        isIntegrated = false
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
                    descArgs = listOf(name, version),
                    isIntegrated = false
                )
            } else {
                val currentScope = scope ?: emptyList()
                val isLSPosed = name.contains("LSPosed", ignoreCase = true)
                val isInScope = if (isLSPosed) {
                    currentScope.contains(Consts.packageName)
                } else {
                    currentScope.isEmpty() || currentScope.contains(Consts.packageName)
                }

                if (!isInScope) {
                    return BannerEvaluation(
                        isConnected = true,
                        isInjectable = false,
                        frameworkName = name,
                        frameworkVersion = version,
                        titleRes = if (isLSPosed) R.string.framework_lsposed_not_in_scope_title else R.string.framework_sb_not_in_scope_title,
                        descRes = if (isLSPosed) R.string.framework_lsposed_not_in_scope_desc else R.string.framework_sb_not_in_scope_desc,
                        isIntegrated = false
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
                    descArgs = listOf(name, version),
                    isIntegrated = false
                )
            }
        } else {
            return BannerEvaluation(
                isConnected = false,
                isInjectable = false,
                frameworkName = "",
                frameworkVersion = "",
                titleRes = R.string.framework_inactive_title,
                descRes = R.string.framework_inactive_desc,
                isIntegrated = false
            )
        }
    }
}
