package io.github.s1ddhants1.swiftbackupprem.ui.component

import androidx.compose.foundation.clickable
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
    onLabelClick: (() -> Unit)? = null,
    thumbContent: (@Composable () -> Unit)? = null,
) {
    val titleAlpha = if (enabled) 1f else 0.38f
    val subtitleAlpha = if (enabled) 0.6f else 0.38f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onLabelClick == null) {
                    Modifier.toggleable(
                        value = if (enabled) pref else false,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = onPrefChange
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .tvFocusable(),
        horizontalArrangement = Arrangement.spacedBy(15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .weight(0.95f, true)
                .then(
                    if (onLabelClick != null) {
                        Modifier.clickable(enabled = enabled, onClick = onLabelClick)
                    } else {
                        Modifier
                    }
                )
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

        if (onLabelClick != null) {
            VerticalDivider(
                modifier = Modifier
                    .height(32.dp)
                    .padding(horizontal = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        } else {
            Spacer(Modifier.weight(0.05f, true))
        }

        Switch(
            checked = if (enabled) pref else false,
            enabled = enabled,
            onCheckedChange = if (onLabelClick != null) onPrefChange else null,
            thumbContent = thumbContent
        )
    }
}
