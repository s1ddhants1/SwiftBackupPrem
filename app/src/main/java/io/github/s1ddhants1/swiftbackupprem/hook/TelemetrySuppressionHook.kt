package io.github.s1ddhants1.swiftbackupprem.hook

import android.content.Context
import android.util.Log
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt

object TelemetrySuppressionHook : HookHandler {

    private val SCHEDULER_METHODS = setOf("upload", "schedule", "logAndUpdateState")
    private val CRASH_METHODS = setOf("log", "logException", "logFatalException", "openSession", "writeToLog", "finalizeSessions", "installHandler")
    private val SESSION_METHODS = setOf("register", "appForeground", "appBackground", "logSession", "attemptLoggingSessionEvent")

    private val COMBINED_CLASSES = setOf(
        "com.google.firebase.crashlytics.FirebaseCrashlytics",
        "com.google.firebase.analytics.FirebaseAnalytics"
    )

    val nullTargets: Map<String, Set<String>> = mapOf(
        "com.google.android.datatransport.runtime.TransportRuntime" to setOf("send"),
        "com.google.android.datatransport.runtime.scheduling.jobscheduling.Uploader" to SCHEDULER_METHODS,
        "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoScheduler" to SCHEDULER_METHODS,
        "com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerScheduler" to SCHEDULER_METHODS,
        "com.google.firebase.crashlytics.FirebaseCrashlytics" to setOf("recordException", "log", "setCustomKey", "setUserId", "sendUnsentReports", "deleteUnsentReports"),
        "com.google.firebase.crashlytics.internal.common.CrashlyticsCore" to CRASH_METHODS,
        "com.google.firebase.crashlytics.internal.common.CrashlyticsController" to CRASH_METHODS,
        "com.google.firebase.crashlytics.ndk.FirebaseCrashlyticsNdk" to CRASH_METHODS,
        "com.google.firebase.analytics.FirebaseAnalytics" to setOf("logEvent", "setUserProperty", "setUserId", "setCurrentScreen", "resetAnalyticsData"),
        "com.google.firebase.sessions.FirebaseSessions" to SESSION_METHODS,
        "com.google.firebase.sessions.SessionFirelogPublisherImpl" to SESSION_METHODS,
        "com.google.firebase.sessions.SessionFirelogPublisher" to SESSION_METHODS,
        "com.google.firebase.installations.FirebaseInstallations" to setOf("delete"),
        "com.google.firebase.installations.remote.FirebaseInstallationServiceClient" to setOf("deleteFirebaseInstallation"),
    )

    override fun apply(
        module: HookContext,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        if (!prefs.disableTelemetry) return
        Log.d(Consts.TAG, "Applying telemetry, analytics, and tracking suppression")

        for ((className, methods) in nullTargets) {
            if (className in COMBINED_CLASSES) continue
            hookMethodsNull(module, classLoader, className) { it in methods }
        }

        listOf(
            "com.google.android.gms.measurement.AppMeasurement",
            "com.google.android.gms.measurement.internal.zzhd",
            "com.google.android.gms.measurement.internal.zzha"
        ).forEach { name ->
            hookMethodsNull(module, classLoader, name) { it.startsWith("logEvent") || it.startsWith("setUserProperty") }
        }

        val crashlyticsNullMethods = nullTargets["com.google.firebase.crashlytics.FirebaseCrashlytics"] ?: emptySet()
        attempt("hook FirebaseCrashlytics (combined)", silent = true) {
            val clazz = classLoader.loadClass("com.google.firebase.crashlytics.FirebaseCrashlytics")
            val tasksClass = classLoader.loadClass("com.google.android.gms.tasks.Tasks")
            val falseTask = tasksClass.getMethod("forResult", Any::class.java).invoke(null, java.lang.Boolean.FALSE)
            clazz.declaredMethods.forEach { m ->
                when {
                    m.name in crashlyticsNullMethods -> hookNull(module, m, "telemetry-crashlytics-${m.name}")
                    m.name.startsWith("set") && m.name.endsWith("CollectionEnabled") -> hookDisableCollection(module, m, "telemetry-crashlytics-${m.name}")
                    m.name == "checkForUnsentReports" -> attempt("hook ${m.name}", silent = true) {
                        module.hookTracked(m, idPrefix = "telemetry-crashlytics-checkForUnsentReports", priority = PRIORITY_LOWEST).intercept { falseTask }
                    }
                }
            }
        }

        val analyticsNullMethods = nullTargets["com.google.firebase.analytics.FirebaseAnalytics"] ?: emptySet()
        attempt("hook FirebaseAnalytics (combined)", silent = true) {
            val clazz = classLoader.loadClass("com.google.firebase.analytics.FirebaseAnalytics")
            clazz.declaredMethods.forEach { m ->
                when {
                    m.name in analyticsNullMethods -> hookNull(module, m, "telemetry-analytics-${m.name}")
                    m.name.startsWith("set") && m.name.endsWith("CollectionEnabled") -> hookDisableCollection(module, m, "telemetry-analytics-${m.name}")
                }
            }
        }

        attempt("hook TransportRuntime.schedule", silent = true) {
            val runtimeClass = classLoader.loadClass("com.google.android.datatransport.runtime.TransportRuntime")
            runtimeClass.declaredMethods.filter { it.name == "schedule" }.forEach { m ->
                module.hookTracked(m, idPrefix = "telemetry-transport-schedule", priority = PRIORITY_LOWEST).intercept { chain ->
                    chain.args.lastOrNull()?.let { cb ->
                        attempt("invoke schedule callback", silent = true) {
                            cb.javaClass.getMethod("onSchedule", Exception::class.java).invoke(cb, null)
                        }
                    }
                    null
                }
            }
        }

        attempt("hook CctTransportBackend.send", silent = true) {
            val cctClass = classLoader.loadClass("com.google.android.datatransport.cct.CctTransportBackend")
            val backendResponseClass = classLoader.loadClass("com.google.android.datatransport.runtime.backends.BackendResponse")
            val dummyResponse = backendResponseClass.getMethod("ok", Long::class.javaPrimitiveType).invoke(null, 1000L)
            cctClass.declaredMethods.filter { it.name == "send" || (it.parameterCount == 1 && it.returnType.simpleName == "BackendResponse") }.forEach { m ->
                module.hookTracked(m, idPrefix = "telemetry-cct-${m.name}", priority = PRIORITY_LOWEST).intercept { dummyResponse }
            }
        }
    }

    private fun hookNull(module: HookContext, m: java.lang.reflect.Method, idPrefix: String) {
        attempt("hook ${m.name}", silent = true) {
            module.hookTracked(m, idPrefix = idPrefix, priority = PRIORITY_LOWEST).intercept { null }
        }
    }

    private fun hookDisableCollection(module: HookContext, m: java.lang.reflect.Method, idPrefix: String) {
        attempt("hook ${m.name}", silent = true) {
            module.hookTracked(m, idPrefix = idPrefix, priority = PRIORITY_LOWEST).intercept { chain ->
                if (chain.args.isNotEmpty()) chain.proceed(arrayOf(java.lang.Boolean.FALSE)) else chain.proceed()
            }
        }
    }

    private fun hookMethodsNull(
        module: HookContext,
        classLoader: ClassLoader,
        className: String,
        predicate: (String) -> Boolean
    ) {
        val simpleName = className.substringAfterLast('.')
        attempt("hook telemetry in $className", silent = true) {
            val clazz = classLoader.loadClass(className)
            clazz.declaredMethods.filter { predicate(it.name) }.forEach { m ->
                hookNull(module, m, "telemetry-$simpleName-${m.name}")
            }
        }
    }
}
