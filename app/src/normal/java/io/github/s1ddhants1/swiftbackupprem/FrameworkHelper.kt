package io.github.s1ddhants1.swiftbackupprem

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.s1ddhants1.swiftbackupprem.util.LSPatchHelper
import io.github.s1ddhants1.swiftbackupprem.util.LSPatchHelper.BannerEvaluation
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object FrameworkHelper {
    private val _evaluation = MutableStateFlow<BannerEvaluation?>(null)
    val evaluation: StateFlow<BannerEvaluation?> = _evaluation.asStateFlow()

    @Volatile
    private var currentService: XposedService? = null

    val frameworkName: String get() = currentService?.frameworkName.orEmpty()

    fun init(app: Application) {
        attempt("register XposedServiceHelper listener") {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    Log.i(Consts.TAG, "Connected to Xposed Framework: ${service.frameworkName} v${service.frameworkVersion} (API ${service.apiVersion})")
                    currentService = service
                    refreshEvaluation(app)
                }

                override fun onServiceDied(service: XposedService) {
                    Log.w(Consts.TAG, "Xposed Framework Service disconnected")
                    if (currentService == service) {
                        currentService = null
                    }
                    refreshEvaluation(app)
                }
            })
        }
    }

    fun isModuleActive(): Boolean = currentService != null

    fun refreshEvaluation(context: Context): BannerEvaluation {
        val service = currentService
        val eval = LSPatchHelper.evaluateFrameworkStatus(
            context = context,
            frameworkName = attempt("get frameworkName", silent = true) { service?.frameworkName },
            frameworkVersion = attempt("get frameworkVersion", silent = true) { service?.frameworkVersion },
            scope = attempt("get service scope", silent = true) { service?.scope },
            isServiceBound = service != null
        )
        _evaluation.value = eval
        return eval
    }

    fun syncPreferences(
        context: Context,
        localPrefs: android.content.SharedPreferences,
        onPrefsUpdated: (io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager) -> Unit
    ) {
        val service = currentService
        val eval = _evaluation.value
        if (service != null && eval != null && !eval.isIntegrated) {
            attempt("retrieve remote preferences from XposedService") {
                val remotePrefs = service.getRemotePreferences(Consts.PREFS_SETTINGS)
                val remoteMgr = io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager(remotePrefs, backupPrefs = localPrefs)
                val localMgr = io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager(localPrefs)
                if (remotePrefs.all.isEmpty()) {
                    if (localPrefs.all.isEmpty()) {
                        localMgr.loadFromFallbackStorage(context)
                    }
                    remoteMgr.applyConfig(localMgr.toConfig())
                } else {
                    localMgr.applyConfig(remoteMgr.toConfig())
                }
                remoteMgr.onPreferenceChanged = { remoteMgr.saveToFallbackStorageAsync(context) }
                remoteMgr.saveToFallbackStorageAsync(context)
                onPrefsUpdated(remoteMgr)
            }
        } else {
            val fallbackMgr = io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager(localPrefs).apply {
                if (localPrefs.all.isEmpty()) {
                    loadFromFallbackStorage(context)
                }
                onPreferenceChanged = { saveToFallbackStorageAsync(context) }
            }
            onPrefsUpdated(fallbackMgr)
        }
    }
}
