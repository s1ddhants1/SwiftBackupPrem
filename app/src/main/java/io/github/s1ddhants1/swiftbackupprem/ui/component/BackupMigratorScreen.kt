package io.github.s1ddhants1.swiftbackupprem.ui.component

import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.s1ddhants1.swiftbackupprem.R
import io.github.s1ddhants1.swiftbackupprem.ui.BackupMigratorUiEvent
import io.github.s1ddhants1.swiftbackupprem.ui.BackupMigratorViewModel
import io.github.s1ddhants1.swiftbackupprem.ui.TargetModeSelection
import io.github.s1ddhants1.swiftbackupprem.util.AppUtils
import io.github.s1ddhants1.swiftbackupprem.util.BackupMigratorEngine
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import java.io.File

enum class ExperimentalHubTab {
    LOCAL_MIGRATION,
    CLOUD_DISCOVERY
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupMigratorScreen(
    viewModel: BackupMigratorViewModel,
    prefs: PreferencesManager,
    modifier: Modifier = Modifier,
    initialTab: ExperimentalHubTab = ExperimentalHubTab.LOCAL_MIGRATION
) {
    var selectedTab by remember { mutableStateOf(initialTab) }

    Column(modifier = modifier.fillMaxSize()) {
        PrimaryTabRow(
            selectedTabIndex = selectedTab.ordinal,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Tab(
                selected = selectedTab == ExperimentalHubTab.LOCAL_MIGRATION,
                onClick = { selectedTab = ExperimentalHubTab.LOCAL_MIGRATION },
                text = { Text(stringResource(R.string.tab_local_migrator), fontWeight = FontWeight.SemiBold) },
                icon = { Icon(Icons.Default.FolderZip, contentDescription = null) }
            )
            Tab(
                selected = selectedTab == ExperimentalHubTab.CLOUD_DISCOVERY,
                onClick = { selectedTab = ExperimentalHubTab.CLOUD_DISCOVERY },
                text = { Text(stringResource(R.string.tab_cloud_discovery), fontWeight = FontWeight.SemiBold) },
                icon = { Icon(Icons.Default.CloudSync, contentDescription = null) }
            )
        }

        AnimatedContent(targetState = selectedTab, label = "TabTransition") { tab ->
            when (tab) {
                ExperimentalHubTab.LOCAL_MIGRATION -> LocalMigrationTabContent(viewModel = viewModel, prefs = prefs)
                ExperimentalHubTab.CLOUD_DISCOVERY -> CloudDiscoveryTabContent(viewModel = viewModel, prefs = prefs)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalMigrationTabContent(
    viewModel: BackupMigratorViewModel,
    prefs: PreferencesManager
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        viewModel.autoDetectSourceUids(context)
    }

    val dirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.setSourcePath(AppUtils.resolvePathFromTreeUri(uri))
    }

    val targetDirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.setTargetPath(AppUtils.resolvePathFromTreeUri(uri))
    }

    val hasStoragePermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(vertical = 8.dp)
    ) {
        if (!hasStoragePermission) {
            MigratorCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(
                            text = stringResource(R.string.migrator_permission_required_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Text(
                        text = stringResource(R.string.migrator_permission_required_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Button(
                        onClick = {
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = android.net.Uri.parse("package:" + context.packageName)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                context.startActivity(intent)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.btn_grant_storage_permission))
                    }
                }
            }
        }

        AnimatedVisibility(visible = state.errorMessage != null) {
            state.errorMessage?.let { err ->
                MigratorCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(err, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        MigratorSectionCard(title = stringResource(R.string.migrator_step1_title)) {
            OutlinedTextField(
                value = state.sourcePath,
                onValueChange = { viewModel.setSourcePath(it) },
                label = { Text(stringResource(R.string.migrator_source_path_label)) },
                placeholder = { Text(stringResource(R.string.migrator_source_path_placeholder)) },
                trailingIcon = {
                    IconButton(onClick = { dirPickerLauncher.launch(null) }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = stringResource(R.string.cd_pick_directory))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
        }

        MigratorSectionCard(title = stringResource(R.string.migrator_step2_title)) {
            OutlinedTextField(
                value = state.sourceUid,
                onValueChange = { viewModel.setSourceUid(it) },
                label = { Text(stringResource(R.string.migrator_source_uid_label)) },
                placeholder = { Text(stringResource(R.string.migrator_source_uid_placeholder)) },
                trailingIcon = {
                    IconButton(onClick = {
                        clipboardManager.getText()?.text?.let { viewModel.setSourceUid(it) }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.cd_paste_uid))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.migrator_detected_uids),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = { viewModel.autoDetectSourceUids(context) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.cd_detect_uids),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            val chipColors = FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                labelColor = MaterialTheme.colorScheme.onSurface,
                iconColor = MaterialTheme.colorScheme.primary,
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
            )

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isAnonSelected = state.sourceUid == BackupMigratorEngine.SWIFT_BACKUP_ANONYMOUS_UID
                FilterChip(
                    selected = isAnonSelected,
                    onClick = {
                        viewModel.setSourceUid(if (isAnonSelected) "" else BackupMigratorEngine.SWIFT_BACKUP_ANONYMOUS_UID)
                    },
                    label = {
                        Text(
                            text = stringResource(R.string.migrator_chip_anon_key),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isAnonSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    shape = RoundedCornerShape(8.dp),
                    colors = chipColors
                )

                state.detectedUids.filter { it != BackupMigratorEngine.SWIFT_BACKUP_ANONYMOUS_UID }.forEach { uid ->
                    val isUidSelected = state.sourceUid == uid
                    FilterChip(
                        selected = isUidSelected,
                        onClick = {
                            viewModel.setSourceUid(if (isUidSelected) "" else uid)
                        },
                        label = {
                            Text(
                                text = if (uid.length > 14) uid.take(12) + "..." else uid,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isUidSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        shape = RoundedCornerShape(8.dp),
                        colors = chipColors
                    )
                }
            }
        }

        MigratorSectionCard(title = stringResource(R.string.migrator_step3_title)) {
            ModeSelectionItem(
                title = stringResource(R.string.migrator_mode_anon_title),
                subtitle = stringResource(R.string.migrator_mode_anon_desc),
                selected = state.targetMode == TargetModeSelection.ANONYMOUS,
                onClick = { viewModel.setTargetMode(TargetModeSelection.ANONYMOUS) }
            )

            ModeSelectionItem(
                title = stringResource(R.string.migrator_mode_custom_title),
                subtitle = stringResource(R.string.migrator_mode_custom_desc),
                selected = state.targetMode == TargetModeSelection.CUSTOM_UID,
                onClick = { viewModel.setTargetMode(TargetModeSelection.CUSTOM_UID) }
            )

            if (state.targetMode == TargetModeSelection.CUSTOM_UID) {
                OutlinedTextField(
                    value = state.customTargetUid,
                    onValueChange = { viewModel.setCustomTargetUid(it) },
                    label = { Text(stringResource(R.string.migrator_target_uid_label)) },
                    placeholder = { Text(stringResource(R.string.migrator_target_uid_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            }

            ModeSelectionItem(
                title = stringResource(R.string.migrator_mode_unencrypted_title),
                subtitle = stringResource(R.string.migrator_mode_unencrypted_desc),
                selected = state.targetMode == TargetModeSelection.UNENCRYPTED,
                onClick = { viewModel.setTargetMode(TargetModeSelection.UNENCRYPTED) }
            )

            AnimatedVisibility(
                visible = state.targetMode == TargetModeSelection.UNENCRYPTED,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, top = 2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { viewModel.setExportPortableFormats(!state.exportPortableFormats) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (state.exportPortableFormats) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Checkbox(
                            checked = state.exportPortableFormats,
                            onCheckedChange = { viewModel.setExportPortableFormats(it) }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.migrator_mode_portable_checkbox),
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.migrator_mode_portable_checkbox_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        MigratorSectionCard(title = stringResource(R.string.migrator_step4_title)) {
            OutlinedTextField(
                value = state.targetPath,
                onValueChange = { viewModel.setTargetPath(it) },
                label = { Text(stringResource(R.string.migrator_output_path_label)) },
                placeholder = { Text(stringResource(R.string.migrator_output_path_placeholder)) },
                trailingIcon = {
                    IconButton(onClick = { targetDirPickerLauncher.launch(null) }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = stringResource(R.string.cd_pick_output_directory))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
        }

        if (state.isMigrating) {
            MigratorCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = state.currentStep.ifBlank { stringResource(R.string.migrator_status_processing) },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (state.totalCount > 0) {
                            Text(
                                text = "${state.processedCount} / ${state.totalCount}",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    if (state.totalCount > 0) {
                        LinearProgressIndicator(
                            progress = { state.processedCount.toFloat() / state.totalCount.toFloat() },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                        )
                    }

                    if (state.currentItem.isNotBlank()) {
                        Text(
                            text = state.currentItem,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            Button(
                onClick = { viewModel.startMigration(context, prefs) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(50.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = state.sourcePath.isNotBlank() && state.sourceUid.isNotBlank() && state.targetPath.isNotBlank()
            ) {
                Icon(Icons.Default.SyncAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.migrator_btn_start), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        state.migrationResult?.let { result ->
            MigratorCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            imageVector = if (result.success) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (result.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = if (result.success) stringResource(R.string.migrator_result_success_title)
                            else stringResource(R.string.migrator_result_fail_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = stringResource(
                            R.string.migrator_result_summary_format,
                            result.totalAppsMigrated,
                            result.totalFoldersMigrated,
                            result.targetAccountHash ?: "N/A"
                        ) + if (result.totalSyncedToFirebase > 0) stringResource(R.string.migrator_result_synced_format, result.totalSyncedToFirebase) else "",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Text(
                        text = stringResource(R.string.migrator_result_output_format, result.outputDirectory.absolutePath),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        if (state.logs.isNotEmpty()) {
            MigratorCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.migrator_logs_title),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        LazyColumn {
                            items(state.logs) { log ->
                                Text(
                                    text = log,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun CloudDiscoveryTabContent(
    viewModel: BackupMigratorViewModel,
    prefs: PreferencesManager
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val isCustomFirebase = prefs.customFirebaseApp
    val isDbConfigured = prefs.firebaseDatabaseUrl.isNotBlank()
    val canConfigureSync = isCustomFirebase && isDbConfigured
    val isSyncEnabled = canConfigureSync && prefs.syncMetadataToFirebase

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is BackupMigratorUiEvent.FirebaseSyncResult -> {
                    val appContext = context.applicationContext
                    val msg = if (event.totalSynced > 0) {
                        appContext.getString(R.string.msg_sync_firebase_success, event.totalSynced)
                    } else if (event.error != null) {
                        appContext.getString(R.string.msg_sync_firebase_failed, event.error)
                    } else {
                        appContext.getString(R.string.msg_sync_firebase_no_new)
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val isCloudCapable = isCustomFirebase || prefs.unlockLocalCloudFeatures

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(vertical = 8.dp)
    ) {
        MigratorCard {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingsSwitch(
                    label = stringResource(R.string.pref_drive_oauth_scope_title),
                    secondaryLabel = if (isCustomFirebase) {
                        stringResource(R.string.pref_drive_oauth_scope_desc)
                    } else {
                        stringResource(R.string.pref_enable_drive_discovery_requires_custom_firebase)
                    },
                    pref = if (isCustomFirebase) prefs.enableGoogleDriveScope else false,
                    enabled = isCustomFirebase,
                    onPrefChange = { prefs.enableGoogleDriveScope = it }
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                SettingsSwitch(
                    label = stringResource(R.string.pref_cloud_discovery_title),
                    secondaryLabel = if (isCloudCapable) {
                        stringResource(R.string.pref_cloud_discovery_desc)
                    } else {
                        stringResource(R.string.pref_enable_drive_discovery_requires_custom_firebase)
                    },
                    pref = if (isCloudCapable) prefs.enableCloudDiscovery else false,
                    enabled = isCloudCapable,
                    onPrefChange = {
                        prefs.enableCloudDiscovery = it
                        if (!it) {
                            prefs.enableSnapshotInjection = false
                        }
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                val isCloudDiscoveryEnabled = isCloudCapable && prefs.enableCloudDiscovery
                SettingsSwitch(
                    label = stringResource(R.string.pref_snapshot_injection_title),
                    secondaryLabel = when {
                        !isCloudCapable -> stringResource(R.string.pref_enable_drive_discovery_requires_custom_firebase)
                        !prefs.enableCloudDiscovery -> stringResource(R.string.pref_snapshot_injection_requires_discovery)
                        else -> stringResource(R.string.pref_snapshot_injection_desc)
                    },
                    pref = if (isCloudDiscoveryEnabled) prefs.enableSnapshotInjection else false,
                    enabled = isCloudDiscoveryEnabled,
                    onPrefChange = { prefs.enableSnapshotInjection = it }
                )
            }
        }

        MigratorCard {
            Column(modifier = Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, tint = if (isSyncEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_firebase_rtdb),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isDbConfigured) prefs.firebaseDatabaseUrl else stringResource(R.string.pref_rtdb_not_configured),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                SettingsSwitch(
                    label = stringResource(R.string.pref_sync_metadata_firebase_title),
                    secondaryLabel = when {
                        !isCustomFirebase -> stringResource(R.string.pref_enable_drive_discovery_requires_custom_firebase)
                        !isDbConfigured -> stringResource(R.string.pref_rtdb_requires_config)
                        else -> stringResource(R.string.pref_sync_metadata_firebase_desc)
                    },
                    pref = if (canConfigureSync) prefs.syncMetadataToFirebase else false,
                    enabled = canConfigureSync,
                    onPrefChange = { prefs.syncMetadataToFirebase = it }
                )

                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Button(
                        onClick = {
                            if (state.isSyncingFirebase || !isSyncEnabled) return@Button
                            val appContext = context.applicationContext
                            Toast.makeText(appContext, appContext.getString(R.string.msg_sync_firebase_started), Toast.LENGTH_SHORT).show()
                            viewModel.syncFirebaseAll(appContext, prefs)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        enabled = isSyncEnabled && !state.isSyncingFirebase
                    ) {
                        if (state.isSyncingFirebase) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.btn_syncing))
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.btn_sync_all_firebase), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        MigratorCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = stringResource(R.string.cloud_cache_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = stringResource(R.string.cloud_cache_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        try {
                            val appContext = context.applicationContext
                            val cacheFile = File(Environment.getExternalStorageDirectory(), "SwiftBackup/cloud_discovered_cache.json")
                            if (cacheFile.exists()) cacheFile.delete()
                            val fallbackFile = File("/sdcard/SwiftBackup/cloud_discovered_cache.json")
                            if (fallbackFile.exists()) fallbackFile.delete()
                            Toast.makeText(appContext, appContext.getString(R.string.msg_cloud_cache_cleared), Toast.LENGTH_SHORT).show()
                        } catch (_: Throwable) {}
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_clear_cloud_cache))
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun ModeSelectionItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MigratorCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = containerColor),
        shape = RoundedCornerShape(16.dp),
        content = content
    )
}

@Composable
private fun MigratorSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    MigratorCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}
