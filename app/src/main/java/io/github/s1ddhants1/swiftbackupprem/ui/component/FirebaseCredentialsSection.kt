package io.github.s1ddhants1.swiftbackupprem.ui.component

import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.R
import io.github.s1ddhants1.swiftbackupprem.util.AppUtils
import io.github.s1ddhants1.swiftbackupprem.util.FirebaseConfigValidator
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager

const val GOOGLE_DRIVE_OAUTH_SCOPE = "https://www.googleapis.com/auth/drive.file"

const val FIREBASE_DATABASE_RULES = "{\n" +
        "  \"rules\": {\n" +
        "    \"users\": {\n" +
        "      \"\$uid\": {\n" +
        "        \".read\": \"\$uid === auth.uid\",\n" +
        "        \".write\": \"\$uid === auth.uid\"\n" +
        "      }\n" +
        "    }\n" +
        "  }\n" +
        "}"

@Composable
fun FirebaseCredentialsSection(
    prefs: PreferencesManager,
    onImportGoogleServices: (Uri) -> Unit,
    onFinish: () -> Unit = {}
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    val copyWithFeedback: (String, String) -> Unit = { text, label ->
        clipboardManager.setText(AnnotatedString(text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
        }
    }

    val pickJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            onImportGoogleServices(uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.firebase_tools_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = { pickJsonLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.btn_import_json), fontWeight = FontWeight.SemiBold)
                        }

                        Text(
                            text = stringResource(R.string.firebase_consoles_label),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CredentialActionButton(
                                text = stringResource(R.string.btn_firebase_console),
                                onClick = { uriHandler.openUri("https://console.firebase.google.com/") },
                                modifier = Modifier.weight(1f),
                                icon = Icons.AutoMirrored.Filled.Launch
                            )
                            CredentialActionButton(
                                text = stringResource(R.string.btn_cloud_console),
                                onClick = { uriHandler.openUri("https://console.cloud.google.com/apis/credentials") },
                                modifier = Modifier.weight(1f),
                                icon = Icons.AutoMirrored.Filled.Launch
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CredentialActionButton(
                                text = stringResource(R.string.btn_enable_drive_api),
                                onClick = {
                                    val url = if (prefs.projectId.isNotBlank())
                                        "https://console.cloud.google.com/apis/library/drive.googleapis.com?project=${prefs.projectId}"
                                    else
                                        "https://console.cloud.google.com/apis/library/drive.googleapis.com"
                                    uriHandler.openUri(url)
                                },
                                modifier = Modifier.weight(1f),
                                icon = Icons.AutoMirrored.Filled.Launch
                            )
                            CredentialActionButton(
                                text = stringResource(R.string.btn_oauth_scopes),
                                onClick = {
                                    val url = if (prefs.projectId.isNotBlank())
                                        "https://console.cloud.google.com/auth/scopes?project=${prefs.projectId}"
                                    else
                                        "https://console.cloud.google.com/auth/scopes"
                                    uriHandler.openUri(url)
                                },
                                modifier = Modifier.weight(1f),
                                icon = Icons.AutoMirrored.Filled.Launch
                            )
                        }

                        Text(
                            text = stringResource(R.string.firebase_copy_helpers_label),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CredentialActionButton(
                                text = stringResource(R.string.btn_copy_package),
                                onClick = { copyWithFeedback(Consts.packageName, "Package name") },
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.ContentCopy
                            )
                            CredentialActionButton(
                                text = stringResource(R.string.btn_copy_fingerprint),
                                onClick = { copyWithFeedback(AppUtils.randomFingerprint(), "Fingerprint") },
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.ContentCopy
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CredentialActionButton(
                                text = stringResource(R.string.btn_copy_rules),
                                onClick = { copyWithFeedback(FIREBASE_DATABASE_RULES, "Database rules") },
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.ContentCopy
                            )
                            CredentialActionButton(
                                text = stringResource(R.string.btn_copy_scope),
                                onClick = { copyWithFeedback(GOOGLE_DRIVE_OAUTH_SCOPE, "Drive OAuth scope") },
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.ContentCopy
                            )
                        }
                    }
                }

                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VpnKey,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.wizard_step6_manual_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

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
    }
}

@Composable
fun CredentialActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    fontSize: TextUnit = 12.sp,
    iconSize: Dp = 14.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 38.dp),
        shape = RoundedCornerShape(10.dp),
        contentPadding = contentPadding
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = text,
            fontSize = fontSize,
            textAlign = TextAlign.Center,
            softWrap = true,
            maxLines = 2,
            lineHeight = 13.sp
        )
    }
}

@Deprecated("Use FirebaseCredentialsSection instead")
@Composable
fun GuidedSetupWizard(
    prefs: PreferencesManager,
    onImportGoogleServices: (Uri) -> Unit,
    onFinish: () -> Unit = {}
) = FirebaseCredentialsSection(prefs = prefs, onImportGoogleServices = onImportGoogleServices, onFinish = onFinish)
