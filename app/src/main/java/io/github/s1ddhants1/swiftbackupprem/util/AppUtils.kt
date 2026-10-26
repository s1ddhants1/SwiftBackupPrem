package io.github.s1ddhants1.swiftbackupprem.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.FrameworkHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object AppUtils {
    private val chars = ('A'..'F') + ('0'..'9')

    private val PACKAGE_NAME_REGEX = Regex(
        "^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+$"
    )

    fun isValidPackageName(name: String): Boolean =
        name.length in 2..256 && PACKAGE_NAME_REGEX.matches(name)

    fun randomFingerprint(): String =
        List(20) { chars.random().toString() + chars.random() }.joinToString(":")

    fun openSwiftBackup(context: Context) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(Consts.packageName)
            ?: context.packageManager.getLeanbackLaunchIntentForPackage(Consts.packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(Consts.packageName)
            }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        attempt("launch Swift Backup") { context.startActivity(launchIntent) }
    }

    suspend fun forceStopSwiftBackup(
        context: Context,
        prefs: PreferencesManager? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): Boolean {
        val wasRunning = FrameworkHelper.isTargetRunning(Consts.packageName)
        if (wasRunning == false) {
            return true
        }

        prefs?.requestForceStop() ?: run {
            attempt("send force stop preference", silent = true) {
                val local = context.getSharedPreferences(Consts.PREFS_SETTINGS, Context.MODE_PRIVATE)
                PreferencesManager(local).requestForceStop()
            }
        }

        attempt("send force-stop broadcast", silent = true) {
            val intent = Intent(Consts.actionForceStop).apply {
                setPackage(Consts.packageName)
            }
            context.sendBroadcast(intent)
        }

        val stopped = withContext(ioDispatcher) {
            var isStopped = false
            for (i in 0 until 10) {
                delay(50)
                val stillRunning = FrameworkHelper.isTargetRunning(Consts.packageName)
                if (stillRunning == false) {
                    isStopped = true
                    break
                }
            }
            isStopped || (wasRunning == null && FrameworkHelper.isModuleActive()) || wasRunning == true
        }
        if (stopped) return true

        attempt("open Swift Backup app settings") {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", Consts.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
        return false
    }

    fun sanitizeUrl(urlStr: String): String {
        return try {
            val uri = java.net.URI(urlStr)
            var sanitized = urlStr
            val userInfo = uri.userInfo
            if (!userInfo.isNullOrBlank()) {
                sanitized = sanitized.replace(userInfo, "***:***")
            }
            sanitized.replace(Regex("(?i)(auth|token|access_token|key|secret|password|signature)=([^&\\s]+)"), "$1=***")
        } catch (_: Throwable) {
            urlStr.replace(Regex("(?i)(auth|token|access_token|key|secret|password|signature)=([^&\\s]+)"), "$1=***")
        }
    }

    fun resolvePathFromTreeUri(uri: Uri): String {
        return try {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            val split = docId.split(":")
            val type = split[0]
            val relPath = if (split.size > 1) split[1] else ""
            if ("primary".equals(type, ignoreCase = true)) {
                if (relPath.isNotBlank()) "/storage/emulated/0/$relPath" else "/storage/emulated/0"
            } else {
                "/storage/$type/$relPath"
            }
        } catch (_: Exception) {
            val raw = uri.path ?: uri.toString()
            val decoded = Uri.decode(raw)
            if (decoded.contains(":")) {
                val rel = decoded.substringAfter(":")
                if (decoded.contains("primary")) "/storage/emulated/0/$rel" else "/storage/$rel"
            } else decoded
        }
    }
}
