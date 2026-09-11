package io.github.s1ddhants1.swiftbackupprem.ui.component

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.R
import io.github.s1ddhants1.swiftbackupprem.ui.component.wizard.*
import io.github.s1ddhants1.swiftbackupprem.util.AppUtils
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager

@Composable
fun GuidedSetupWizard(
    prefs: PreferencesManager,
    onImportGoogleServices: (Uri) -> Unit,
    onFinish: () -> Unit = {}
) {
    val isConfigured = prefs.toConfig().isCompleteFirebaseConfig
    var userOverrodeCollapse by remember { mutableStateOf<Boolean?>(null) }
    val isCollapsed = userOverrodeCollapse ?: (prefs.firebaseSetupFinished || isConfigured)
    var currentStep by remember(prefs.firebaseSetupFinished, isConfigured) {
        mutableIntStateOf(if (prefs.firebaseSetupFinished || isConfigured) TOTAL_WIZARD_STEPS else 1)
    }

    val uriHandler = LocalUriHandler.current
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    val pickJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            onImportGoogleServices(uri)
            userOverrodeCollapse = false
            currentStep = TOTAL_WIZARD_STEPS
        }
    }

    AnimatedContent(targetState = isCollapsed, label = "CollapseAnimation") { collapsed ->
        if (collapsed) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = if (isConfigured) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = if (isConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = stringResource(
                                if (isConfigured) R.string.wizard_firebase_configured_title
                                else R.string.wizard_credentials_incomplete_title
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(
                                if (isConfigured) R.string.wizard_firebase_configured_desc
                                else R.string.wizard_credentials_incomplete_desc
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                WizardActionButton(
                    text = stringResource(R.string.btn_edit_setup),
                    onClick = {
                        currentStep = TOTAL_WIZARD_STEPS
                        userOverrodeCollapse = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Default.Edit
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                WizardProgressHeader(currentStep = currentStep)

                AnimatedContent(targetState = currentStep, label = "StepAnimation") { step ->
                    when (step) {
                        1 -> Step1CreateProject(
                            onOpenConsole = { uriHandler.openUri("https://console.firebase.google.com/") },
                            onImportClick = { pickJsonLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                            onNext = { currentStep = 2 }
                        )
                        2 -> Step2Database(
                            onOpenConsole = { uriHandler.openUri("https://console.firebase.google.com/u/0/") },
                            onCopyRules = { clipboardManager.setText(AnnotatedString(FIREBASE_DATABASE_RULES)) },
                            onBack = { currentStep = 1 },
                            onNext = { currentStep = 3 }
                        )
                        3 -> Step3Authentication(
                            onOpenConsole = { uriHandler.openUri("https://console.firebase.google.com/u/0/") },
                            onBack = { currentStep = 2 },
                            onNext = { currentStep = 4 }
                        )
                        4 -> Step4AndroidOAuth(
                            prefs = prefs,
                            onCopyPackageName = { clipboardManager.setText(AnnotatedString(Consts.packageName)) },
                            onCopyFingerprint = { clipboardManager.setText(AnnotatedString(AppUtils.randomFingerprint())) },
                            onOpenCloudConsole = { uriHandler.openUri("https://console.cloud.google.com/apis/credentials") },
                            onBack = { currentStep = 3 },
                            onNext = { currentStep = 5 }
                        )
                        5 -> Step5DriveApiAndScopes(
                            prefs = prefs,
                            onEnableDriveApi = {
                                val url = if (prefs.projectId.isNotBlank())
                                    "https://console.cloud.google.com/apis/library/drive.googleapis.com?project=${prefs.projectId}"
                                else
                                    "https://console.cloud.google.com/apis/library/drive.googleapis.com"
                                uriHandler.openUri(url)
                            },
                            onOpenScopesConsole = {
                                val url = if (prefs.projectId.isNotBlank())
                                    "https://console.cloud.google.com/auth/scopes?project=${prefs.projectId}"
                                else
                                    "https://console.cloud.google.com/auth/scopes"
                                uriHandler.openUri(url)
                            },
                            onCopyScope = { clipboardManager.setText(AnnotatedString(GOOGLE_DRIVE_OAUTH_SCOPE)) },
                            onBack = { currentStep = 4 },
                            onNext = { currentStep = 6 }
                        )
                        6 -> Step6ReviewFinish(
                            prefs = prefs,
                            onImportClick = { pickJsonLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                            onBack = { currentStep = 5 },
                            onFinish = {
                                prefs.firebaseSetupFinished = true
                                userOverrodeCollapse = true
                                onFinish()
                            }
                        )
                    }
                }
            }
        }
    }
}
