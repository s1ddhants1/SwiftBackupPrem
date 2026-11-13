package io.github.s1ddhants1.swiftbackupprem.hook

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.Keep
import androidx.core.net.toUri
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import org.json.JSONObject

@Keep
object GoogleDriveScopeHook : HookHandler {

    private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val FULL_DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
    private const val ENCODED_DRIVE_FILE_SCOPE = "https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.file"
    private const val ENCODED_FULL_DRIVE_SCOPE = "https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive"

    @Volatile
    private var preferences: PreferencesManager? = null

    override fun apply(
        module: HookContext,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        preferences = prefs
        hookTokenProvider(module, targets.cloudGmsClass, context)
        if (!prefs.customFirebaseApp || !prefs.enableGoogleDriveScope) {
            Log.d(Consts.TAG, "Google Drive scope upgrade is disabled (requires custom Firebase app and Google Drive OAuth expansion)")
            return
        }

        Log.d(Consts.TAG, "Applying GoogleDriveScopeHook (OAuth scope expansion to full Drive)")
        hookOAuthHelper(module, targets.oauthHelperClass)
        hookAuthRequestBuilder(module, targets.authRequestBuilderClass)
        hookUriBuilder(module)
        hookActivityStart(module, classLoader)
        hookIntentSetData(module)
        hookGmsScope(module, classLoader)
    }

    private fun isScopeExpansionEnabled(): Boolean {
        val p = preferences ?: return false
        return p.customFirebaseApp && p.enableGoogleDriveScope
    }

    private fun hookOAuthHelper(module: HookContext, clazz: Class<*>?) {
        if (clazz == null) return
        attempt("hook OAuthHelper constructors (${clazz.name})") {
            for (ctor in clazz.declaredConstructors) {
                module.hookTracked(
                    ctor,
                    idPrefix = "drive-scope-oauth-helper"
                ).intercept { chain ->
                    if (!isScopeExpansionEnabled()) return@intercept chain.proceed()
                    var modified = false
                    val newArgs = chain.args.map { arg ->
                        when {
                            arg is List<*> && arg.contains(DRIVE_FILE_SCOPE) -> {
                                modified = true
                                arg.map { if (it == DRIVE_FILE_SCOPE) FULL_DRIVE_SCOPE else it }
                            }
                            arg is String && arg == DRIVE_FILE_SCOPE -> {
                                modified = true
                                FULL_DRIVE_SCOPE
                            }
                            else -> arg
                        }
                    }.toTypedArray()

                    if (modified) {
                        Log.d(Consts.TAG, "Upgraded Google Drive scope in OAuthHelper constructor")
                        chain.proceed(newArgs)
                    } else {
                        chain.proceed()
                    }
                }
            }
        }
    }

    private fun hookAuthRequestBuilder(module: HookContext, clazz: Class<*>?) {
        if (clazz == null) return
        attempt("hook AuthRequestBuilder methods (${clazz.name})") {
            for (m in clazz.declaredMethods) {
                if (m.returnType != Void.TYPE && m.parameterCount == 0 && m.returnType != clazz) {
                    module.hookTracked(
                        m,
                        idPrefix = "drive-scope-auth-builder-${m.name}"
                    ).intercept { chain ->
                        if (!isScopeExpansionEnabled()) return@intercept chain.proceed()
                        val target = chain.thisObject
                        if (target != null) {
                            for (field in target.javaClass.declaredFields) {
                                try {
                                    field.isAccessible = true
                                    val value = field.get(target)
                                    if (value is String && value.contains(DRIVE_FILE_SCOPE)) {
                                        field.set(target, value.replace(DRIVE_FILE_SCOPE, FULL_DRIVE_SCOPE))
                                    } else if (value is List<*> && value.contains(DRIVE_FILE_SCOPE)) {
                                        field.set(target, value.map { if (it == DRIVE_FILE_SCOPE) FULL_DRIVE_SCOPE else it })
                                    }
                                } catch (_: Throwable) {}
                            }
                        }
                        chain.proceed()
                    }
                }
            }
        }
    }

    private fun hookUriBuilder(module: HookContext) {
        attempt("hook Uri.Builder.appendQueryParameter") {
            val m = Uri.Builder::class.java.getDeclaredMethod("appendQueryParameter", String::class.java, String::class.java)
            module.hookTracked(
                m,
                idPrefix = "drive-scope-uri-builder"
            ).intercept { chain ->
                if (!isScopeExpansionEnabled()) return@intercept chain.proceed()
                val key = chain.getArg(0) as? String
                val value = chain.getArg(1) as? String
                when {
                    key == "scope" && value != null && value.contains("drive.file") -> {
                        val upgraded = upgradeScopeString(value)
                        chain.proceed(arrayOf(key, upgraded))
                    }
                    key == "prompt" && value != "consent" -> chain.proceed(arrayOf(key, "consent"))
                    else -> chain.proceed()
                }
            }
        }
    }

    private fun hookActivityStart(module: HookContext, cl: ClassLoader) {
        attempt("hook NoGmsSignInActivity.startActivityForResult", silent = true) {
            val activityClass = cl.loadClass("org.swiftapps.swiftbackup.cloud.connect.NoGmsSignInActivity")
            for (m in activityClass.declaredMethods) {
                if (m.parameterTypes.isNotEmpty() && m.parameterTypes[0] == Intent::class.java &&
                    (m.name == "startActivityForResult" || (m.parameterCount >= 2 && m.returnType == Void.TYPE))) {
                    module.hookTracked(
                        m,
                        idPrefix = "drive-scope-nogms-activity-${m.name}"
                    ).intercept { chain ->
                        if (isScopeExpansionEnabled()) {
                            (chain.getArg(0) as? Intent)?.let { upgradeIntentUri(it) }
                        }
                        chain.proceed()
                    }
                }
            }
        }
    }

    private fun hookIntentSetData(module: HookContext) {
        attempt("hook Intent.setData / setDataAndNormalize") {
            for (methodName in listOf("setData", "setDataAndNormalize")) {
                val m = Intent::class.java.getDeclaredMethod(methodName, Uri::class.java)
                module.hookTracked(
                    m,
                    idPrefix = "drive-scope-intent-$methodName"
                ).intercept { chain ->
                    if (!isScopeExpansionEnabled()) return@intercept chain.proceed()
                    val uri = chain.getArg(0) as? Uri
                    if (uri != null && (uri.toString().contains("drive.file") || uri.toString().contains("accounts.google.com"))) {
                        chain.proceed(arrayOf(upgradeUri(uri)))
                    } else {
                        chain.proceed()
                    }
                }
            }
        }
    }

    private fun upgradeIntentUri(intent: Intent) {
        intent.data?.let { intent.data = upgradeUri(it) }
    }

    private fun upgradeScopeString(s: String): String = s
        .replace(ENCODED_DRIVE_FILE_SCOPE, ENCODED_FULL_DRIVE_SCOPE)
        .replace(DRIVE_FILE_SCOPE, FULL_DRIVE_SCOPE)
        .replace("drive.file", "drive")

    fun upgradeUri(uri: Uri): Uri {
        var uriStr = upgradeScopeString(uri.toString())
        if (uriStr.contains("accounts.google.com/o/oauth2") && !uriStr.contains("prompt=consent")) {
            uriStr = if (uriStr.contains("prompt=")) uriStr.replace(Regex("prompt=[^&]*"), "prompt=consent")
            else "$uriStr&prompt=consent"
        }
        return uriStr.toUri()
    }

    private fun hookGmsScope(module: HookContext, cl: ClassLoader) {
        attempt("hook GMS Scope class fallback", silent = true) {
            val scopeClass = cl.loadClass("com.google.android.gms.common.api.Scope")
            for (ctor in scopeClass.declaredConstructors) {
                if (ctor.parameterCount == 1 && ctor.parameterTypes[0] == String::class.java) {
                    module.hookTracked(
                        ctor,
                        idPrefix = "drive-scope-gms-scope-ctor"
                    ).intercept { chain ->
                        if (isScopeExpansionEnabled() && chain.getArg(0) == DRIVE_FILE_SCOPE) {
                            chain.proceed(arrayOf(FULL_DRIVE_SCOPE))
                        } else {
                            chain.proceed()
                        }
                    }
                }
            }
        }
    }

    private fun hookTokenProvider(module: HookContext, clazz: Class<*>?, context: Context) {
        if (clazz == null) return
        val method = clazz.declaredMethods.firstOrNull { it.name == "invoke" && it.parameterCount == 0 } ?: return
        attempt("hook Google Drive token provider (${clazz.name})") {
            module.hookTracked(
                method,
                idPrefix = "drive-token-provider",
                deoptimize = true
            ).intercept { chain ->
                val target = chain.thisObject
                val isDriveLambda = target != null && isGoogleDriveReceiver(target)
                if (!isDriveLambda) {
                    return@intercept chain.proceed()
                }

                val original = chain.proceed()
                if (original is String && original.isNotBlank()) {
                    return@intercept original
                }

                val receiver = getLambdaReceiver(target)
                if (receiver != null) {
                    for (f in receiver.javaClass.declaredFields) {
                        try {
                            f.isAccessible = true
                            val v = f.get(receiver)
                            if (v is String && (v.startsWith("ya29.") || (v.length > 20 && !v.contains(" ") && !v.contains("{")))) {
                                return@intercept v
                            }
                        } catch (_: Throwable) {}
                    }
                }

                val defaultPrefs = attempt("get default prefs for token fallback", silent = true) {
                    context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
                }
                if (defaultPrefs != null) {
                    val noGmsToken = defaultPrefs.getString("nogms_access_token", null)
                    if (!noGmsToken.isNullOrBlank()) {
                        return@intercept noGmsToken
                    }
                    val authStateJson = defaultPrefs.getString("nogms_auth_state", null)
                    if (!authStateJson.isNullOrBlank()) {
                        try {
                            val json = JSONObject(authStateJson)
                            val lastToken = json.optJSONObject("mLastTokenResponse")
                            val token = lastToken?.optString("access_token")
                            if (!token.isNullOrBlank()) {
                                defaultPrefs.edit().putString("nogms_access_token", token).apply()
                                return@intercept token
                            }
                        } catch (_: Throwable) {}
                    }
                }

                original
            }
        }
    }

    private fun getLambdaReceiver(target: Any): Any? {
        val f = target.javaClass.superclass?.declaredFields?.firstOrNull { it.name == "receiver" }
            ?: target.javaClass.declaredFields.firstOrNull { it.name == "receiver" }
            ?: return null
        return try {
            f.isAccessible = true
            f.get(target)
        } catch (_: Throwable) {
            null
        }
    }

    private fun isGoogleDriveReceiver(target: Any): Boolean {
        val receiver = getLambdaReceiver(target) ?: return false
        val rClass = receiver.javaClass
        if (rClass.name.contains("GClient")) return true
        val hasDriveEnum = rClass.declaredFields.any { f ->
            try {
                f.isAccessible = true
                val v = f.get(receiver)
                v != null && v.toString().contains("GoogleDrive")
            } catch (_: Throwable) {
                false
            }
        }
        if (hasDriveEnum) return true
        return rClass.declaredFields.any { it.name == "j" && it.type == String::class.java } &&
            rClass.declaredFields.any { it.name == "i" }
    }
}
