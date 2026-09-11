package io.github.s1ddhants1.swiftbackupprem.util

import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Detects whether the current device is an Android TV (leanback) environment.
 * Caches the result per-composition to avoid repeated PackageManager queries.
 */
@Composable
fun rememberIsTvDevice(): Boolean {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                configuration.uiMode and
                Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    }
}

/**
 * Makes a composable D-pad focusable with a visible focus ring on Android TV.
 * On non-TV devices this is a no-op passthrough to avoid visual clutter.
 */
fun Modifier.tvFocusable(
    interactionSource: MutableInteractionSource? = null,
): Modifier = composed {
    val isTv = rememberIsTvDevice()
    if (!isTv) return@composed this

    val source = interactionSource ?: remember { MutableInteractionSource() }
    val isFocused = source.collectIsFocusedAsState()
    val focusColor = MaterialTheme.colorScheme.primary

    this
        .focusable(interactionSource = source)
        .then(
            if (isFocused.value) {
                Modifier.drawBehind {
                    drawRoundRect(
                        color = focusColor,
                        cornerRadius = CornerRadius(12.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            } else {
                Modifier
            }
        )
}
