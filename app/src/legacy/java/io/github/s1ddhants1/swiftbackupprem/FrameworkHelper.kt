package io.github.s1ddhants1.swiftbackupprem

import android.app.Application
import android.content.Context
import io.github.s1ddhants1.swiftbackupprem.util.LSPatchHelper
import io.github.s1ddhants1.swiftbackupprem.util.LSPatchHelper.BannerEvaluation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object FrameworkHelper {
    private val _evaluation = MutableStateFlow<BannerEvaluation?>(null)
    val evaluation: StateFlow<BannerEvaluation?> = _evaluation.asStateFlow()

    val frameworkName: String get() = ""

    fun init(app: Application) {
        refreshEvaluation(app)
    }

    fun isModuleActive(): Boolean = App.isModuleActive()

    fun refreshEvaluation(context: Context): BannerEvaluation {
        val eval = LSPatchHelper.evaluateFrameworkStatus(
            context = context,
            isModuleActive = App.isModuleActive()
        )
        _evaluation.value = eval
        return eval
    }

    fun syncPreferences(
        context: Context,
        localPrefs: android.content.SharedPreferences,
        onPrefsUpdated: (io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager) -> Unit
    ) {
        val mgr = io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager(localPrefs).apply {
            if (localPrefs.all.isEmpty()) {
                loadFromFallbackStorage(context)
            }
            onPreferenceChanged = {
                saveToFallbackStorageAsync(context)
                makePrefsWorldReadable(context)
            }
        }
        makePrefsWorldReadable(context)
        onPrefsUpdated(mgr)
    }

    private fun makePrefsWorldReadable(context: Context) {
        io.github.s1ddhants1.swiftbackupprem.util.attempt("make world-readable prefs", silent = true) {
            val file = java.io.File(context.filesDir.parentFile, "shared_prefs/${Consts.PREFS_SETTINGS}.xml")
            if (file.exists()) {
                file.setReadable(true, false)
                file.parentFile?.setReadable(true, false)
                file.parentFile?.setExecutable(true, false)
            }
        }
    }
}
