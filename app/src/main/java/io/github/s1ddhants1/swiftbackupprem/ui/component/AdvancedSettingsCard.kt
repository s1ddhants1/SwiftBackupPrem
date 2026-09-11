package io.github.s1ddhants1.swiftbackupprem.ui.component

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.s1ddhants1.swiftbackupprem.R
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager

@Composable
fun AdvancedSettingsCard(
    prefs: PreferencesManager,
    modifier: Modifier = Modifier,
    isFrameworkConnected: Boolean = true
) {
    var showAdvancedFeatures by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(enabled = true)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAdvancedFeatures = !showAdvancedFeatures }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(Icons.Default.Science, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.section_advanced_features),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.section_advanced_features_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (showAdvancedFeatures) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = showAdvancedFeatures,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    SettingsSwitch(
                        label = stringResource(R.string.pref_unlock_local_cloud_features_title),
                        secondaryLabel = stringResource(R.string.pref_unlock_local_cloud_features_desc),
                        pref = prefs.unlockLocalCloudFeatures,
                        enabled = true,
                        onPrefChange = {
                            prefs.unlockLocalCloudFeatures = it
                            if (it) {
                                prefs.enableCloudDiscovery = true
                                prefs.enableSnapshotInjection = true
                            }
                        }
                    )

                    AnimatedVisibility(
                        visible = prefs.unlockLocalCloudFeatures,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            SettingsTextField(
                                label = stringResource(R.string.pref_local_account_custom_uid_title),
                                pref = prefs.localAccountCustomUid,
                                onPrefChange = { prefs.localAccountCustomUid = it.trim() },
                                isRequired = false,
                                showStatusIcon = false,
                                imeAction = ImeAction.Done
                            )
                            Text(
                                text = stringResource(R.string.pref_local_account_custom_uid_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
