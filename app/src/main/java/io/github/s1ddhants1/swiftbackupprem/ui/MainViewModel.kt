package io.github.s1ddhants1.swiftbackupprem.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.s1ddhants1.swiftbackupprem.data.ConfigRepository
import io.github.s1ddhants1.swiftbackupprem.data.ConfigRepositoryImpl
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

import io.github.s1ddhants1.swiftbackupprem.R
import io.github.s1ddhants1.swiftbackupprem.util.LSPatchHelper

data class MainUiState(
    val isFrameworkConnected: Boolean = false,
    val frameworkName: String = "",
    val frameworkVersion: String = "",
    val isInjectable: Boolean = false,
    val titleRes: Int = R.string.framework_inactive_title,
    val titleArgs: List<String> = emptyList(),
    val descRes: Int = R.string.framework_inactive_desc,
    val descArgs: List<String> = emptyList()
)

sealed interface MainUiEvent {
    data class ConfigExported(val success: Boolean, val error: String? = null) : MainUiEvent
    data class ConfigImported(val success: Boolean, val error: String? = null) : MainUiEvent
}

class MainViewModel(
    private val configRepository: ConfigRepository = ConfigRepositoryImpl()
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _events = Channel<MainUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun updateFrameworkEvaluation(evaluation: LSPatchHelper.BannerEvaluation) {
        _uiState.update {
            it.copy(
                isFrameworkConnected = evaluation.isConnected,
                frameworkName = evaluation.frameworkName,
                frameworkVersion = evaluation.frameworkVersion,
                isInjectable = evaluation.isInjectable,
                titleRes = evaluation.titleRes,
                titleArgs = evaluation.titleArgs,
                descRes = evaluation.descRes,
                descArgs = evaluation.descArgs
            )
        }
    }

    fun onFrameworkConnected(name: String, version: String) {
        _uiState.update {
            it.copy(
                isFrameworkConnected = true,
                frameworkName = name,
                frameworkVersion = version,
                isInjectable = true
            )
        }
    }

    fun onFrameworkDisconnected() {
        _uiState.update {
            it.copy(
                isFrameworkConnected = false,
                isInjectable = false,
                frameworkName = "",
                frameworkVersion = ""
            )
        }
    }

    fun exportConfig(
        contentResolver: ContentResolver,
        uri: Uri,
        prefs: PreferencesManager
    ) {
        viewModelScope.launch {
            val result = configRepository.exportConfig(contentResolver, uri, prefs.toConfig())
            _events.send(
                MainUiEvent.ConfigExported(
                    success = result.isSuccess,
                    error = result.exceptionOrNull()?.localizedMessage
                )
            )
        }
    }

    fun importConfig(
        contentResolver: ContentResolver,
        uri: Uri,
        prefs: PreferencesManager
    ) {
        viewModelScope.launch {
            val result = configRepository.importConfig(contentResolver, uri, prefs)
            _events.send(
                MainUiEvent.ConfigImported(
                    success = result.isSuccess,
                    error = result.exceptionOrNull()?.localizedMessage
                )
            )
        }
    }

    fun importGoogleServices(
        contentResolver: ContentResolver,
        uri: Uri,
        prefs: PreferencesManager
    ) {
        importConfig(contentResolver, uri, prefs)
    }

    fun parseAndApplyConfig(json: JSONObject, prefs: PreferencesManager) {
        configRepository.parseConfig(json.toString(), prefs)
    }
}
