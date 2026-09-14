package io.github.s1ddhants1.swiftbackupprem.util

import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LSPatchHelperTest {

    @Test
    fun lspatchActionMatchesConstant() {
        assertEquals("org.lsposed.lspatch.action.REQUEST_PUSH", LSPatchHelper.ACTION_REQUEST_PUSH)
    }

    @Test
    fun lspatchConnected_swiftBackupNotInstalled_returnsNotInstalled() {
        val result = LSPatchHelper.evaluateFrameworkStatus(
            frameworkName = "LSPatch",
            frameworkVersion = "0.6",
            scope = listOf(Consts.packageName),
            isServiceBound = true,
            targetStatus = LSPatchHelper.TargetStatus(
                isInstalled = false,
                isPatched = false,
                isModuleEmbedded = false
            )
        )
        assertTrue(result.isConnected)
        assertFalse(result.isInjectable)
        assertEquals(R.string.framework_sb_not_installed_title, result.titleRes)
    }

    @Test
    fun lspatchConnected_swiftBackupNotPatched_returnsNotPatched() {
        val result = LSPatchHelper.evaluateFrameworkStatus(
            frameworkName = "LSPatch",
            frameworkVersion = "0.6",
            scope = listOf(Consts.packageName),
            isServiceBound = true,
            targetStatus = LSPatchHelper.TargetStatus(
                isInstalled = true,
                isPatched = false,
                isModuleEmbedded = false
            )
        )
        assertTrue(result.isConnected)
        assertFalse(result.isInjectable)
        assertEquals(R.string.framework_sb_not_patched_title, result.titleRes)
    }

    @Test
    fun lspatchConnected_swiftBackupPatched_notInScope_returnsNotInScope() {
        val result = LSPatchHelper.evaluateFrameworkStatus(
            frameworkName = "LSPatch",
            frameworkVersion = "0.6",
            scope = emptyList(),
            isServiceBound = true,
            targetStatus = LSPatchHelper.TargetStatus(
                isInstalled = true,
                isPatched = true,
                isModuleEmbedded = false,
                useManager = true
            )
        )
        assertTrue(result.isConnected)
        assertFalse(result.isInjectable)
        assertEquals(R.string.framework_sb_not_in_scope_title, result.titleRes)
    }

    @Test
    fun lspatchConnected_swiftBackupPatched_inScope_returnsActive() {
        val result = LSPatchHelper.evaluateFrameworkStatus(
            frameworkName = "LSPatch",
            frameworkVersion = "0.6",
            scope = listOf(Consts.packageName),
            isServiceBound = true,
            targetStatus = LSPatchHelper.TargetStatus(
                isInstalled = true,
                isPatched = true,
                isModuleEmbedded = false,
                useManager = true
            )
        )
        assertTrue(result.isConnected)
        assertTrue(result.isInjectable)
        assertEquals(R.string.framework_active_title_dynamic, result.titleRes)
    }

    @Test
    fun lspatchConnected_portableMode_moduleEmbedded_returnsActive() {
        val result = LSPatchHelper.evaluateFrameworkStatus(
            frameworkName = "LSPatch",
            frameworkVersion = "0.6",
            scope = emptyList(),
            isServiceBound = true,
            targetStatus = LSPatchHelper.TargetStatus(
                isInstalled = true,
                isPatched = true,
                isModuleEmbedded = true,
                useManager = false
            )
        )
        assertTrue(result.isConnected)
        assertTrue(result.isInjectable)
        assertEquals(R.string.framework_active_title_dynamic, result.titleRes)
    }

    @Test
    fun serviceNotBound_targetEmbedded_returnsEmbeddedActive() {
        val result = LSPatchHelper.evaluateFrameworkStatus(
            frameworkName = null,
            frameworkVersion = null,
            scope = null,
            isServiceBound = false,
            targetStatus = LSPatchHelper.TargetStatus(
                isInstalled = true,
                isPatched = true,
                isModuleEmbedded = true,
                useManager = false
            )
        )
        assertTrue(result.isConnected)
        assertTrue(result.isInjectable)
        assertEquals(R.string.framework_lspatch_embedded_active_title, result.titleRes)
    }

    @Test
    fun serviceNotBound_targetNotEmbedded_returnsInactive() {
        val result = LSPatchHelper.evaluateFrameworkStatus(
            frameworkName = null,
            frameworkVersion = null,
            scope = null,
            isServiceBound = false,
            targetStatus = LSPatchHelper.TargetStatus(
                isInstalled = false,
                isPatched = false,
                isModuleEmbedded = false
            )
        )
        assertFalse(result.isConnected)
        assertFalse(result.isInjectable)
        assertEquals(R.string.framework_inactive_title, result.titleRes)
    }

    @Test
    fun lsposedConnected_emptyScope_returnsActive() {
        val result = LSPatchHelper.evaluateFrameworkStatus(
            frameworkName = "LSPosed",
            frameworkVersion = "1.9.3",
            scope = emptyList(),
            isServiceBound = true,
            targetStatus = LSPatchHelper.TargetStatus(
                isInstalled = true,
                isPatched = false,
                isModuleEmbedded = false
            )
        )
        assertTrue(result.isConnected)
        assertTrue(result.isInjectable)
        assertEquals(R.string.framework_active_title_dynamic, result.titleRes)
    }

    @Test
    fun lsposedConnected_packageNotInScope_returnsNotInScope() {
        val result = LSPatchHelper.evaluateFrameworkStatus(
            frameworkName = "LSPosed",
            frameworkVersion = "1.9.3",
            scope = listOf("com.other.app"),
            isServiceBound = true,
            targetStatus = LSPatchHelper.TargetStatus(
                isInstalled = true,
                isPatched = false,
                isModuleEmbedded = false
            )
        )
        assertTrue(result.isConnected)
        assertFalse(result.isInjectable)
        assertEquals(R.string.framework_sb_not_in_scope_title, result.titleRes)
    }
}
