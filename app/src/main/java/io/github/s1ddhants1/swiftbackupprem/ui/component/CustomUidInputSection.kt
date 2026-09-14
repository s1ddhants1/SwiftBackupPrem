package io.github.s1ddhants1.swiftbackupprem.ui.component

import androidx.compose.animation.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
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
    prefs: PreferencesManager? = null,
    keyModeTitle: String? = null,
    anonymousUidValue: String = BackupMigratorEngine.SWIFT_BACKUP_ANONYMOUS_UID,
    anonymousChipLabel: String = stringResource(R.string.migrator_chip_anon_key),
    customChipLabel: String = stringResource(R.string.pref_key_mode_custom),
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

    val nonAnonymousDetectedUids = remember(effectiveDetectedUids, anonymousUidValue) {
        effectiveDetectedUids.filter {
            it.isNotBlank() && it != BackupMigratorEngine.SWIFT_BACKUP_ANONYMOUS_UID && it != anonymousUidValue
        }
    }

    LaunchedEffect(Unit) {
        if (uid.isBlank() && anonymousUidValue.isNotBlank()) {
            onUidChange(anonymousUidValue)
        }
    }

    var isManualCustomMode by remember {
        mutableStateOf(
            uid.isNotBlank() &&
            uid != anonymousUidValue &&
            uid !in nonAnonymousDetectedUids
        )
    }

    val isAnonSelected = !isManualCustomMode && (
        uid == anonymousUidValue || (uid.isBlank() && anonymousUidValue.isBlank())
    )

    val isCustomKeySelected = isManualCustomMode || (
        uid.isNotBlank() &&
        uid != anonymousUidValue &&
        uid !in nonAnonymousDetectedUids
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (!keyModeTitle.isNullOrBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = keyModeTitle,
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
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = isAnonSelected,
                onClick = {
                    isManualCustomMode = false
                    onUidChange(anonymousUidValue)
                },
                label = {
                    Text(
                        text = anonymousChipLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isAnonSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                },
                leadingIcon = if (isAnonSelected) {
                    {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else null,
                shape = RoundedCornerShape(8.dp)
            )

            nonAnonymousDetectedUids.forEach { itemUid ->
                val isSelected = !isManualCustomMode && uid == itemUid
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        isManualCustomMode = false
                        onUidChange(itemUid)
                    },
                    label = {
                        Text(
                            text = if (itemUid.length > 14) itemUid.take(12) + "..." else itemUid,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    leadingIcon = if (isSelected) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else null,
                    shape = RoundedCornerShape(8.dp)
                )
            }

            FilterChip(
                selected = isCustomKeySelected,
                onClick = {
                    isManualCustomMode = true
                    if (uid == anonymousUidValue || uid in nonAnonymousDetectedUids) {
                        onUidChange("")
                    }
                },
                label = {
                    Text(
                        text = customChipLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isCustomKeySelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                },
                leadingIcon = if (isCustomKeySelected) {
                    {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else null,
                shape = RoundedCornerShape(8.dp)
            )
        }

        AnimatedVisibility(
            visible = isCustomKeySelected,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = if (uid == anonymousUidValue) "" else uid,
                    onValueChange = onUidChange,
                    label = { Text(label) },
                    placeholder = { Text(placeholder) },
                    trailingIcon = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            if (uid.isNotBlank() && uid != anonymousUidValue) {
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
            }
        }
    }
}
