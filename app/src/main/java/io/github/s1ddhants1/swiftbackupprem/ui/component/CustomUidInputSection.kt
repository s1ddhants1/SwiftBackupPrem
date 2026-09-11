package io.github.s1ddhants1.swiftbackupprem.ui.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.s1ddhants1.swiftbackupprem.R
import io.github.s1ddhants1.swiftbackupprem.domain.usecase.DetectCandidateUidsUseCase
import io.github.s1ddhants1.swiftbackupprem.util.BackupMigratorEngine
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomUidInputSection(
    uid: String,
    onUidChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.migrator_source_uid_label),
    placeholder: String = stringResource(R.string.migrator_source_uid_placeholder),
    helperText: String? = null,
    detectedUids: List<String>? = null,
    onRefreshUids: (() -> Unit)? = null,
    showAnonymousChip: Boolean = true,
    prefs: PreferencesManager? = null,
    detectCandidateUidsUseCase: DetectCandidateUidsUseCase = remember { DetectCandidateUidsUseCase() }
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    var internalDetectedUids by remember { mutableStateOf<List<String>>(emptyList()) }
    var isDetectingInternal by remember { mutableStateOf(false) }

    fun refreshInternal() {
        if (isDetectingInternal) return
        isDetectingInternal = true
        coroutineScope.launch {
            val list = withContext(Dispatchers.IO) {
                detectCandidateUidsUseCase(context, context.classLoader, prefs = prefs)
            }
            internalDetectedUids = list
            isDetectingInternal = false
        }
    }

    LaunchedEffect(detectedUids) {
        if (detectedUids == null) {
            refreshInternal()
        }
    }

    val effectiveDetectedUids = detectedUids ?: internalDetectedUids
    val effectiveOnRefresh: () -> Unit = onRefreshUids ?: { refreshInternal() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = uid,
            onValueChange = onUidChange,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            trailingIcon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    if (uid.isNotBlank()) {
                        IconButton(
                            onClick = { onUidChange("") },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = stringResource(R.string.cd_clear_input),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            clipboardManager.getText()?.text?.let { onUidChange(it.trim()) }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentPaste,
                            contentDescription = stringResource(R.string.cd_paste_uid),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done
            ),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )

        if (!helperText.isNullOrBlank()) {
            Text(
                text = helperText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp)
            )
        }

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
                onClick = {
                    if (!isDetectingInternal) {
                        effectiveOnRefresh()
                    }
                },
                modifier = Modifier.size(24.dp)
            ) {
                if (isDetectingInternal) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.cd_detect_uids),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
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
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showAnonymousChip) {
                val isAnonSelected = uid == BackupMigratorEngine.SWIFT_BACKUP_ANONYMOUS_UID
                FilterChip(
                    selected = isAnonSelected,
                    onClick = {
                        onUidChange(if (isAnonSelected) "" else BackupMigratorEngine.SWIFT_BACKUP_ANONYMOUS_UID)
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
            }

            effectiveDetectedUids.filter { it != BackupMigratorEngine.SWIFT_BACKUP_ANONYMOUS_UID }.forEach { itemUid ->
                val isUidSelected = uid == itemUid
                FilterChip(
                    selected = isUidSelected,
                    onClick = {
                        onUidChange(if (isUidSelected) "" else itemUid)
                    },
                    label = {
                        Text(
                            text = if (itemUid.length > 14) itemUid.take(12) + "..." else itemUid,
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
}
