package io.github.s1ddhants1.swiftbackupprem.ui.component.wizard

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.s1ddhants1.swiftbackupprem.R
import io.github.s1ddhants1.swiftbackupprem.ui.component.SettingsTextField
import io.github.s1ddhants1.swiftbackupprem.util.FirebaseConfigValidator
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager

@Composable
fun Step1CreateProject(
    onOpenConsole: () -> Unit,
    onImportClick: () -> Unit,
    onNext: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WizardCard(
            icon = Icons.Default.RocketLaunch,
            iconTint = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.wizard_title_step_1)
        ) {
            Text(
                text = stringResource(R.string.wizard_step1_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            WizardActionButton(
                text = stringResource(R.string.btn_open_console),
                onClick = onOpenConsole,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.AutoMirrored.Filled.Launch,
                isPrimary = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Text(
                text = stringResource(R.string.wizard_or_divider),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }

        WizardCard(
            icon = Icons.Default.UploadFile,
            iconTint = MaterialTheme.colorScheme.secondary,
            title = stringResource(R.string.wizard_one_tap_import_title)
        ) {
            WizardActionButton(
                text = stringResource(R.string.btn_import_json),
                onClick = onImportClick,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Default.UploadFile
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            WizardActionButton(
                text = stringResource(R.string.btn_next),
                onClick = onNext,
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                isPrimary = true,
                isIconAtEnd = true,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
fun Step2Database(
    onOpenConsole: () -> Unit,
    onCopyRules: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WizardCard(
            icon = Icons.Default.Storage,
            iconTint = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.wizard_title_step_2)
        ) {
            Text(
                text = stringResource(R.string.wizard_step2_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WizardActionButton(
                    text = stringResource(R.string.btn_open_console),
                    onClick = onOpenConsole,
                    modifier = Modifier.weight(1f),
                    icon = Icons.AutoMirrored.Filled.Launch,
                    isPrimary = true,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                )
                WizardActionButton(
                    text = stringResource(R.string.btn_copy_rules),
                    onClick = onCopyRules,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.ContentCopy,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                )
            }
        }

        WizardNavRow(onBack = onBack, onNext = onNext)
    }
}

@Composable
fun Step3Authentication(
    onOpenConsole: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WizardCard(
            icon = Icons.Default.Lock,
            iconTint = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.wizard_title_step_3)
        ) {
            Text(
                text = stringResource(R.string.wizard_step3_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            WizardActionButton(
                text = stringResource(R.string.btn_open_console),
                onClick = onOpenConsole,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.AutoMirrored.Filled.Launch,
                isPrimary = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp).padding(top = 2.dp)
                )
                Text(
                    text = stringResource(R.string.wizard_step3_storage_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        WizardNavRow(onBack = onBack, onNext = onNext)
    }
}

@Composable
fun Step4AndroidOAuth(
    prefs: PreferencesManager,
    onCopyPackageName: () -> Unit,
    onCopyFingerprint: () -> Unit,
    onOpenCloudConsole: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WizardCard(
            icon = Icons.Default.VpnKey,
            iconTint = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.wizard_step4_app_title)
        ) {
            Text(
                text = stringResource(R.string.wizard_step4_app_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WizardActionButton(
                    text = stringResource(R.string.btn_copy_package),
                    onClick = onCopyPackageName,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.ContentCopy,
                    fontSize = 12.sp,
                    iconSize = 14.dp,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                )
                WizardActionButton(
                    text = stringResource(R.string.btn_copy_fingerprint),
                    onClick = onCopyFingerprint,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.ContentCopy,
                    fontSize = 12.sp,
                    iconSize = 14.dp,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                )
            }
        }

        WizardCard(
            icon = Icons.Default.CloudQueue,
            iconTint = MaterialTheme.colorScheme.secondary,
            title = stringResource(R.string.wizard_step4_oauth_title)
        ) {
            Text(
                text = stringResource(R.string.wizard_step4_oauth_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            WizardActionButton(
                text = stringResource(R.string.btn_cloud_console),
                onClick = onOpenCloudConsole,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.AutoMirrored.Filled.Launch,
                isPrimary = true,
                fontSize = 12.sp,
                iconSize = 14.dp,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
            )
        }

        SettingsTextField(
            label = stringResource(R.string.label_client_id),
            pref = prefs.clientId,
            onPrefChange = { prefs.clientId = it },
            imeAction = ImeAction.Done,
            showStatusIcon = true,
            validator = FirebaseConfigValidator::isValidClientId
        )

        WizardNavRow(onBack = onBack, onNext = onNext)
    }
}

@Composable
fun Step5DriveApiAndScopes(
    prefs: PreferencesManager,
    onEnableDriveApi: () -> Unit,
    onOpenScopesConsole: () -> Unit,
    onCopyScope: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WizardCard(
            icon = Icons.Default.CloudSync,
            iconTint = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.wizard_step5_drive_api_title)
        ) {
            Text(
                text = stringResource(R.string.wizard_step5_drive_api_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            WizardActionButton(
                text = stringResource(R.string.btn_enable_drive_api),
                onClick = onEnableDriveApi,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.AutoMirrored.Filled.Launch,
                isPrimary = true,
                fontSize = 12.sp,
                iconSize = 14.dp,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
            )
        }

        WizardCard(
            icon = Icons.Default.LockOpen,
            iconTint = MaterialTheme.colorScheme.secondary,
            title = stringResource(R.string.wizard_step5_scopes_title)
        ) {
            Text(
                text = stringResource(R.string.wizard_step5_scopes_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WizardActionButton(
                    text = stringResource(R.string.btn_oauth_scopes),
                    onClick = onOpenScopesConsole,
                    modifier = Modifier.weight(1f),
                    icon = Icons.AutoMirrored.Filled.Launch,
                    isPrimary = true,
                    fontSize = 12.sp,
                    iconSize = 14.dp,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                )
                WizardActionButton(
                    text = stringResource(R.string.btn_copy_scope),
                    onClick = onCopyScope,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.ContentCopy,
                    fontSize = 12.sp,
                    iconSize = 14.dp,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                )
            }
        }

        WizardNavRow(onBack = onBack, onNext = onNext, nextLabel = stringResource(R.string.btn_review))
    }
}

@Composable
fun Step6ReviewFinish(
    prefs: PreferencesManager,
    onImportClick: () -> Unit,
    onBack: () -> Unit,
    onFinish: () -> Unit
) {
    val allValid = FirebaseConfigValidator.isValidConfig(
        projectId = prefs.projectId,
        databaseUrl = prefs.firebaseDatabaseUrl,
        appId = prefs.googleAppId,
        apiKey = prefs.googleApiKey,
        senderId = prefs.gcmDefaultSenderId,
        clientId = prefs.clientId,
        storageBucket = prefs.googleStorageBucket
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (allValid) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (allValid) Icons.Default.CheckCircle else Icons.AutoMirrored.Filled.ListAlt,
                    contentDescription = null,
                    tint = if (allValid) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = stringResource(if (allValid) R.string.wizard_credentials_complete else R.string.wizard_title_step_6),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        WizardActionButton(
            text = stringResource(R.string.btn_import_json),
            onClick = onImportClick,
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Default.UploadFile
        )

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsTextField(
                    label = stringResource(R.string.label_project_id),
                    pref = prefs.projectId,
                    onPrefChange = { prefs.projectId = it },
                    showStatusIcon = true,
                    validator = FirebaseConfigValidator::isValidProjectId
                )

                SettingsTextField(
                    label = stringResource(R.string.label_firebase_db_url),
                    pref = prefs.firebaseDatabaseUrl,
                    onPrefChange = { prefs.firebaseDatabaseUrl = it },
                    keyboardType = KeyboardType.Uri,
                    showStatusIcon = true,
                    validator = FirebaseConfigValidator::isValidDatabaseUrl
                )

                SettingsTextField(
                    label = stringResource(R.string.label_google_app_id),
                    pref = prefs.googleAppId,
                    onPrefChange = { prefs.googleAppId = it },
                    showStatusIcon = true,
                    validator = FirebaseConfigValidator::isValidAppId
                )

                SettingsTextField(
                    label = stringResource(R.string.label_google_api_key),
                    pref = prefs.googleApiKey,
                    onPrefChange = { prefs.googleApiKey = it },
                    showStatusIcon = true,
                    validator = FirebaseConfigValidator::isValidApiKey
                )

                SettingsTextField(
                    label = stringResource(R.string.label_gcm_sender_id),
                    pref = prefs.gcmDefaultSenderId,
                    onPrefChange = { prefs.gcmDefaultSenderId = it },
                    keyboardType = KeyboardType.Number,
                    showStatusIcon = true,
                    validator = FirebaseConfigValidator::isValidSenderId
                )

                SettingsTextField(
                    label = stringResource(R.string.label_client_id),
                    pref = prefs.clientId,
                    onPrefChange = { prefs.clientId = it },
                    showStatusIcon = true,
                    validator = FirebaseConfigValidator::isValidClientId
                )

                SettingsTextField(
                    label = stringResource(R.string.label_google_storage_bucket),
                    pref = prefs.googleStorageBucket,
                    onPrefChange = { prefs.googleStorageBucket = it },
                    imeAction = ImeAction.Done,
                    showStatusIcon = true,
                    isRequired = false,
                    validator = FirebaseConfigValidator::isValidStorageBucket
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            WizardActionButton(
                text = stringResource(R.string.btn_back),
                onClick = onBack,
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            )
            WizardActionButton(
                text = stringResource(R.string.btn_finish_save),
                onClick = onFinish,
                icon = Icons.Default.Save,
                isPrimary = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun WizardCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = iconTint)
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}
