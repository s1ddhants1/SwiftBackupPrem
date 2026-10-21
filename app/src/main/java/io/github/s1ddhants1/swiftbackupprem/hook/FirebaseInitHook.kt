package io.github.s1ddhants1.swiftbackupprem.hook

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import io.github.s1ddhants1.swiftbackupprem.util.attemptOrDefault
import java.lang.reflect.Modifier

object FirebaseInitHook : HookHandler {

    override fun apply(
        module: HookContext,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        val isCustom = prefs.customFirebaseApp && prefs.toConfig().isCompleteFirebaseConfig

        attempt("initialize FirebaseApp") {
            val fbAppClass = classLoader.loadClass("com.google.firebase.FirebaseApp")
            val optClass = classLoader.loadClass("com.google.firebase.FirebaseOptions")
            val ctor = optClass.getDeclaredConstructor(*Array(7) { String::class.java })

            val appId = if (isCustom) prefs.googleAppId else getResourceString(context, "google_app_id", "1:65312358122:android:ea39a9e3952e6522")
            val apiKey = if (isCustom) prefs.googleApiKey else getResourceString(context, "google_api_key", "")
            val dbUrl = if (isCustom) prefs.firebaseDatabaseUrl else getResourceString(context, "firebase_database_url", "https://swift-backup-31751.firebaseio.com")
            val senderId = if (isCustom) prefs.gcmDefaultSenderId else getResourceString(context, "gcm_defaultSenderId", "65312358122")
            val storageBucket = if (isCustom) (prefs.googleStorageBucket.ifBlank { "${prefs.projectId}.appspot.com" }) else getResourceString(context, "google_storage_bucket", "swift-backup-31751.appspot.com")
            val projectId = if (isCustom) prefs.projectId else getResourceString(context, "project_id", "swift-backup-31751")

            val optionsInstance = ctor.newInstance(appId, apiKey, dbUrl, null, senderId, storageBucket, projectId)
            fbAppClass.getDeclaredMethod("initializeApp", Context::class.java, optClass).invoke(null, context, optionsInstance)
            Log.d(Consts.TAG, "Initialized FirebaseApp (custom: $isCustom, project: $projectId)")
        }

        if (isCustom) {
            attempt("hook getGoogleAuthAndroidClientId") {
                val swiftAppClass = classLoader.loadClass("org.swiftapps.swiftbackup.SwiftApp")
                module.hookTracked(
                    swiftAppClass.getDeclaredMethod("getGoogleAuthAndroidClientId"),
                    idPrefix = "firebase-init-clientid",
                    priority = PRIORITY_DEFAULT + 10,
                    deoptimize = true
                ).intercept { prefs.clientId }
            }
        }
    }

    fun applyStaticClientId(targets: ResolvedTargets, prefs: PreferencesManager) {
        if (prefs.customFirebaseApp && prefs.clientId.isNotBlank()) {
            targets.clientIdClass?.let { cIdClass ->
                attempt("set static clientId on $cIdClass") {
                    for (f in cIdClass.declaredFields) {
                        if (f.type == String::class.java && Modifier.isStatic(f.modifiers)) {
                            f.isAccessible = true
                            f.set(null, prefs.clientId)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun getResourceString(ctx: Context, name: String, fallback: String): String {
        val resId = ctx.resources.getIdentifier(name, "string", ctx.packageName)
        return if (resId != 0) attemptOrDefault("getResourceString $name", fallback, silent = true) { ctx.getString(resId) } else fallback
    }
}
