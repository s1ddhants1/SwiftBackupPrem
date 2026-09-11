package io.github.s1ddhants1.swiftbackupprem.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.s1ddhants1.swiftbackupprem.util.tvFocusable

@Composable
fun SettingsSwitch(
    label: String,
    secondaryLabel: String,
    pref: Boolean,
    enabled: Boolean = true,
    onPrefChange: (Boolean) -> Unit,
) {
    val titleAlpha = if (enabled) 1f else 0.38f
    val subtitleAlpha = if (enabled) 0.6f else 0.38f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = if (enabled) pref else false,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onPrefChange
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .tvFocusable(),
        horizontalArrangement = Arrangement.spacedBy(15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(0.95f, true)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Normal,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = titleAlpha)
                ),
                softWrap = true
            )
            Text(
                text = secondaryLabel,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = subtitleAlpha)
                )
            )
        }

        Spacer(Modifier.weight(0.05f, true))

        Switch(
            checked = if (enabled) pref else false,
            enabled = enabled,
            onCheckedChange = null
        )
    }
}
