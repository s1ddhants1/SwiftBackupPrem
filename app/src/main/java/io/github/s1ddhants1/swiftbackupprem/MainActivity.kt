package io.github.s1ddhants1.swiftbackupprem

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.s1ddhants1.swiftbackupprem.ui.BackupMigratorViewModel
import io.github.s1ddhants1.swiftbackupprem.ui.MainUiEvent
import io.github.s1ddhants1.swiftbackupprem.ui.MainViewModel
import io.github.s1ddhants1.swiftbackupprem.ui.component.AboutScreen
import io.github.s1ddhants1.swiftbackupprem.ui.component.AdvancedSettingsCard
import io.github.s1ddhants1.swiftbackupprem.ui.component.BackupMigratorScreen
import io.github.s1ddhants1.swiftbackupprem.ui.component.FirebaseSetupScreen
import io.github.s1ddhants1.swiftbackupprem.ui.component.SettingsSwitch
import io.github.s1ddhants1.swiftbackupprem.ui.theme.Theme
import io.github.s1ddhants1.swiftbackupprem.util.AppUtils
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import io.github.s1ddhants1.swiftbackupprem.util.rememberIsTvDevice
import io.github.s1ddhants1.swiftbackupprem.util.tvFocusable
import kotlinx.coroutines.launch

enum class AppScreen { Settings, About, BackupMigrator, FirebaseSetup }

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val migratorViewModel: BackupMigratorViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current
            val isTv = rememberIsTvDevice()

            LaunchedEffect(isTv) { if (!isTv) enableEdgeToEdge() }

            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val localPrefs = remember { getSharedPreferences(Consts.PREFS_SETTINGS, Context.MODE_PRIVATE) }
            val prefsState = remember {
                val mgr = PreferencesManager(localPrefs)
                if (localPrefs.all.isEmpty()) {
                    mgr.loadFromFallbackStorage(this@MainActivity)
                }
                mgr.onPreferenceChanged = { mgr.saveToFallbackStorageAsync(this@MainActivity) }
                mutableStateOf(mgr)
            }
            val prefs = prefsState.value

            LaunchedEffect(Unit) {
                io.github.s1ddhants1.swiftbackupprem.util.LSPatchHelper.requestServicePush(this@MainActivity)
                App.serviceState.collect { service ->
                    val evaluation = io.github.s1ddhants1.swiftbackupprem.util.LSPatchHelper.evaluateFrameworkStatus(this@MainActivity, service)
                    viewModel.updateFrameworkEvaluation(evaluation)
                    if (service != null) {
                        attempt("retrieve remote preferences from XposedService") {
                            val remotePrefs = service.getRemotePreferences(Consts.PREFS_SETTINGS)
                            val remoteMgr = PreferencesManager(remotePrefs, backupPrefs = localPrefs)
                            val localMgr = PreferencesManager(localPrefs)
                            if (remotePrefs.all.isEmpty()) {
                                if (localPrefs.all.isEmpty()) {
                                    localMgr.loadFromFallbackStorage(this@MainActivity)
                                }
                                remoteMgr.applyConfig(localMgr.toConfig())
                            } else {
                                localMgr.applyConfig(remoteMgr.toConfig())
                            }
                            remoteMgr.onPreferenceChanged = { remoteMgr.saveToFallbackStorageAsync(this@MainActivity) }
                            remoteMgr.saveToFallbackStorageAsync(this@MainActivity)
                            prefsState.value = remoteMgr
                        }
                    } else {
                        val fallbackMgr = PreferencesManager(localPrefs).apply {
                            onPreferenceChanged = { saveToFallbackStorageAsync(this@MainActivity) }
                        }
                        prefsState.value = fallbackMgr
                    }
                }
            }

            androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
                val evaluation = io.github.s1ddhants1.swiftbackupprem.util.LSPatchHelper.evaluateFrameworkStatus(this@MainActivity, App.serviceState.value)
                viewModel.updateFrameworkEvaluation(evaluation)
                onPauseOrDispose {}
            }

            var currentScreen by remember { mutableStateOf(AppScreen.Settings) }
            var showMenu by remember { mutableStateOf(false) }
            val snackbarHostState = remember { SnackbarHostState() }
            val coroutineScope = rememberCoroutineScope()

            BackHandler(enabled = currentScreen != AppScreen.Settings) { currentScreen = AppScreen.Settings }

            LaunchedEffect(Unit) {
                viewModel.events.collect { event ->
                    val msg = when (event) {
                        is MainUiEvent.ConfigExported -> {
                            if (event.success) {
                                prefs.saveToFallbackStorageAsync(this@MainActivity)
                                getString(R.string.msg_config_exported)
                            } else getString(R.string.msg_export_failed, event.error ?: "unknown error")
                        }
                        is MainUiEvent.ConfigImported -> {
                            if (event.success) {
                                prefs.saveToFallbackStorageAsync(this@MainActivity)
                                getString(R.string.msg_config_imported)
                            } else getString(R.string.msg_import_failed, event.error ?: "unknown error")
                        }
                    }
                    snackbarHostState.showSnackbar(msg)
                }
            }

            val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) viewModel.exportConfig(contentResolver, uri, prefs)
            }
            val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) viewModel.importConfig(contentResolver, uri, prefs)
            }

            Theme {
                val isImeVisible = WindowInsets.isImeVisible

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (currentScreen == AppScreen.Settings) {
                                        Icon(painter = painterResource(id = R.drawable.ic_app_bolt), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                    }
                                    Text(
                                        text = stringResource(
                                            when (currentScreen) {
                                                AppScreen.Settings -> R.string.screen_settings
                                                AppScreen.About -> R.string.screen_about
                                                AppScreen.BackupMigrator -> R.string.screen_experimental_hub
                                                AppScreen.FirebaseSetup -> R.string.screen_firebase_setup
                                            }
                                        ),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            },
                            navigationIcon = {
                                if (currentScreen != AppScreen.Settings) {
                                    IconButton(onClick = { currentScreen = AppScreen.Settings }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back_to_settings))
                                    }
                                }
                            },
                            actions = {
                                if (currentScreen == AppScreen.Settings) {
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_menu_options))
                                    }
                                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.menu_export_config)) },
                                            onClick = { showMenu = false; exportLauncher.launch("sbp_config.json") },
                                            leadingIcon = { Icon(Icons.Default.Upload, contentDescription = null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.menu_import_config)) },
                                            onClick = { showMenu = false; importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                                            leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.menu_about)) },
                                            onClick = { showMenu = false; currentScreen = AppScreen.About },
                                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) }
                                        )
                                    }
                                }
                            }
                        )
                    },
                    bottomBar = {
                        AnimatedVisibility(
                            visible = currentScreen == AppScreen.Settings && !isImeVisible,
                            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                        ) {
                            Surface(tonalElevation = 3.dp, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    ActionButton(
                                        text = stringResource(R.string.btn_force_stop),
                                        icon = Icons.Default.PowerSettingsNew,
                                        containerColor = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        coroutineScope.launch {
                                            val stopped = AppUtils.forceStopSwiftBackup(context)
                                            snackbarHostState.showSnackbar(
                                                if (stopped) getString(R.string.msg_swift_backup_force_stopped)
                                                else getString(R.string.msg_opened_swift_backup_settings)
                                            )
                                        }
                                    }
                                    ActionButton(
                                        text = stringResource(R.string.btn_open_app),
                                        icon = Icons.AutoMirrored.Filled.Launch,
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        AppUtils.openSwiftBackup(context)
                                    }
                                }
                            }
                        }
                    }
                ) { paddingValues ->
                    Box(
                        modifier = Modifier
                            .padding(paddingValues)
                            .fillMaxSize()
                            .imePadding()
                    ) {
                        AnimatedContent(targetState = currentScreen, label = "ScreenTransition") { screen ->
                            when (screen) {
                                AppScreen.About -> AboutScreen()
                                AppScreen.BackupMigrator -> BackupMigratorScreen(viewModel = migratorViewModel, prefs = prefs)
                                AppScreen.FirebaseSetup -> FirebaseSetupScreen(
                                    prefs = prefs,
                                    onImportGoogleServices = { uri -> viewModel.importGoogleServices(contentResolver, uri, prefs) }
                                )
                                AppScreen.Settings -> SettingsScreenContent(
                                    prefs = prefs,
                                    uiState = state,
                                    onOpenFirebaseSetup = { currentScreen = AppScreen.FirebaseSetup },
                                    onOpenMigrator = { currentScreen = AppScreen.BackupMigrator }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreenContent(
    prefs: PreferencesManager,
    uiState: io.github.s1ddhants1.swiftbackupprem.ui.MainUiState,
    onOpenFirebaseSetup: () -> Unit,
    onOpenMigrator: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
    ) {
        FrameworkStatusBanner(uiState = uiState)

        OutlinedCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            SettingsSwitch(
                label = stringResource(R.string.pref_enable_premium_title),
                secondaryLabel = stringResource(R.string.pref_enable_premium_subtitle),
                pref = prefs.enablePremium,
                onPrefChange = { prefs.enablePremium = it }
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SettingsSwitch(
                label = stringResource(R.string.pref_disable_telemetry_title),
                secondaryLabel = stringResource(R.string.pref_disable_telemetry_subtitle),
                pref = prefs.disableTelemetry,
                onPrefChange = { prefs.disableTelemetry = it }
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            val firebaseConfigured = prefs.toConfig().isCompleteFirebaseConfig
            SettingsSwitch(
                label = stringResource(R.string.pref_custom_firebase_title),
                secondaryLabel = if (prefs.customFirebaseApp) {
                    if (firebaseConfigured) stringResource(R.string.wizard_credentials_complete)
                    else stringResource(R.string.wizard_credentials_incomplete_desc)
                } else {
                    stringResource(R.string.pref_custom_firebase_subtitle)
                },
                pref = prefs.customFirebaseApp,
                onPrefChange = {
                    prefs.customFirebaseApp = it
                    if (!it) {
                        prefs.enableCloudDiscovery = false
                        prefs.enableGoogleDriveScope = false
                        prefs.enableSnapshotInjection = false
                        prefs.enableBackupRebuilder = false
                        prefs.syncMetadataToFirebase = false
                    }
                },
                onLabelClick = onOpenFirebaseSetup,
                thumbContent = if (prefs.customFirebaseApp) {
                    {
                        Icon(
                            imageVector = if (firebaseConfigured) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (firebaseConfigured) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                } else null
            )
        }

        AdvancedSettingsCard(
            prefs = prefs,
            isFrameworkConnected = uiState.isFrameworkConnected && uiState.isInjectable,
            onOpenMigrator = onOpenMigrator
        )

        Spacer(modifier = Modifier.height(64.dp))
    }
}

@Composable
private fun FrameworkStatusBanner(
    uiState: io.github.s1ddhants1.swiftbackupprem.ui.MainUiState
) {
    val isBannerActive = uiState.isFrameworkConnected && uiState.isInjectable
    val statusBg = if (isBannerActive) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
    }
    val badgeColor = if (isBannerActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val iconTint = if (isBannerActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError

    OutlinedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = statusBg)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(shape = CircleShape, color = badgeColor, modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isBannerActive) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (uiState.titleArgs.isEmpty()) {
                        stringResource(uiState.titleRes)
                    } else {
                        stringResource(uiState.titleRes, *uiState.titleArgs.toTypedArray())
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (uiState.descArgs.isEmpty()) {
                        stringResource(uiState.descRes)
                    } else {
                        stringResource(uiState.descRes, *uiState.descArgs.toTypedArray())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: ImageVector,
    containerColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
        modifier = modifier.heightIn(min = 46.dp).tvFocusable(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, softWrap = true)
    }
}
