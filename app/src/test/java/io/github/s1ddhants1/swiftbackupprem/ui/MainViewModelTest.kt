package io.github.s1ddhants1.swiftbackupprem.ui

import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MainViewModelTest {

    @Test
    fun mainUiStateDefaultsToDisconnected() {
        val viewModel = MainViewModel()
        val state = viewModel.uiState.value
        assertFalse(state.isFrameworkConnected)
        assertEquals("", state.frameworkName)
        assertEquals("", state.frameworkVersion)
    }

    @Test
    fun onFrameworkConnectedUpdatesState() {
        val viewModel = MainViewModel()
        viewModel.onFrameworkConnected("LSPosed", "1.9.3")
        val state = viewModel.uiState.value
        assertTrue(state.isFrameworkConnected)
        assertEquals("LSPosed", state.frameworkName)
        assertEquals("1.9.3", state.frameworkVersion)
    }

    @Test
    fun onFrameworkDisconnectedResetsConnectionFlag() {
        val viewModel = MainViewModel()
        viewModel.onFrameworkConnected("LSPosed", "1.9.3")
        viewModel.onFrameworkDisconnected()
        assertFalse(viewModel.uiState.value.isFrameworkConnected)
    }

    @Test
    fun updateFrameworkEvaluationUpdatesAllFields() {
        val viewModel = MainViewModel()
        val evaluation = io.github.s1ddhants1.swiftbackupprem.util.LSPatchHelper.BannerEvaluation(
            isConnected = true,
            isInjectable = true,
            frameworkName = "LSPatch",
            frameworkVersion = "0.6",
            titleRes = io.github.s1ddhants1.swiftbackupprem.R.string.framework_active_title_dynamic,
            titleArgs = listOf("LSPatch"),
            descRes = io.github.s1ddhants1.swiftbackupprem.R.string.framework_active_desc,
            descArgs = listOf("LSPatch", "0.6")
        )
        viewModel.updateFrameworkEvaluation(evaluation)
        val state = viewModel.uiState.value
        assertTrue(state.isFrameworkConnected)
        assertTrue(state.isInjectable)
        assertEquals("LSPatch", state.frameworkName)
        assertEquals("0.6", state.frameworkVersion)
        assertEquals(io.github.s1ddhants1.swiftbackupprem.R.string.framework_active_title_dynamic, state.titleRes)
        assertEquals(listOf("LSPatch"), state.titleArgs)
    }

    @Test
    fun mainUiEventTypesHoldExpectedPayloads() {
        val exportSuccess = MainUiEvent.ConfigExported(success = true)
        assertTrue(exportSuccess.success)
        assertNull(exportSuccess.error)

        val exportFailure = MainUiEvent.ConfigExported(success = false, error = "Disk full")
        assertFalse(exportFailure.success)
        assertEquals("Disk full", exportFailure.error)

        val importSuccess = MainUiEvent.ConfigImported(success = true)
        assertTrue(importSuccess.success)
        assertNull(importSuccess.error)

        val importFailure = MainUiEvent.ConfigImported(success = false, error = "Invalid format")
        assertFalse(importFailure.success)
        assertEquals("Invalid format", importFailure.error)
    }

    @Test
    fun parseAndApplyConfigDelegatesCorrectly() {
        val prefs = PreferencesManager(null)
        val json = JSONObject(
            """
            {
              "enablePremium": true,
              "disableTelemetry": false,
              "customFirebaseApp": true,
              "projectId": "test-viewmodel-project"
            }
            """.trimIndent()
        )

        val viewModel = MainViewModel()
        viewModel.parseAndApplyConfig(json, prefs)

        assertTrue(prefs.enablePremium)
        assertFalse(prefs.disableTelemetry)
        assertTrue(prefs.customFirebaseApp)
        assertEquals("test-viewmodel-project", prefs.projectId)
    }
}
