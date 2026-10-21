package io.github.s1ddhants1.swiftbackupprem.ui.component

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.s1ddhants1.swiftbackupprem.BuildConfig
import io.github.s1ddhants1.swiftbackupprem.R

private data class Contributor(
    @DrawableRes val avatarRes: Int,
    @StringRes val avatarCdRes: Int,
    val name: String,
    @StringRes val roleRes: Int,
    val githubUrl: String? = null
)

private val CONTRIBUTORS = listOf(
    Contributor(
        avatarRes = R.drawable.ic_avatar_s1ddhants1,
        avatarCdRes = R.string.cd_maintainer_avatar,
        name = "s1ddhants1",
        roleRes = R.string.about_maintainer_role,
        githubUrl = "https://github.com/s1ddhants1"
    ),
    Contributor(
        avatarRes = R.drawable.ic_avatar_juby210,
        avatarCdRes = R.string.cd_author_avatar,
        name = "Juby210",
        roleRes = R.string.about_original_author_role,
        githubUrl = "https://github.com/Juby210"
    )
)

@Composable
fun AboutScreen() {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_logo),
                contentDescription = stringResource(R.string.cd_app_icon),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            SuggestionChip(
                onClick = {},
                label = {
                    Text(
                        text = stringResource(R.string.about_version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            )
        }

        SettingsSectionCard(title = stringResource(R.string.about_authors_maintainers)) {
            CONTRIBUTORS.forEachIndexed { index, contributor ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                ContributorItemRow(contributor = contributor, onOpenUrl = { uriHandler.openUri(it) })
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalIconButton(
                onClick = { uriHandler.openUri("https://github.com/s1ddhants1/SwiftBackupPrem") }
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_github_logo),
                    contentDescription = stringResource(R.string.btn_view_source_github)
                )
            }

            FilledTonalIconButton(
                onClick = { uriHandler.openUri("https://t.me/SwiftBackupPrem") }
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_telegram_logo),
                    contentDescription = stringResource(R.string.btn_telegram_support)
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ContributorItemRow(
    contributor: Contributor,
    onOpenUrl: (String) -> Unit
) {
    ListItem(
        leadingContent = {
            Image(
                painter = painterResource(id = contributor.avatarRes),
                contentDescription = stringResource(contributor.avatarCdRes),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
            )
        },
        headlineContent = {
            Text(
                text = contributor.name,
                style = MaterialTheme.typography.titleMedium
            )
        },
        supportingContent = {
            Text(
                text = stringResource(contributor.roleRes),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        trailingContent = contributor.githubUrl?.let { url ->
            {
                IconButton(onClick = { onOpenUrl(url) }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_github_logo),
                        contentDescription = stringResource(R.string.cd_github_icon),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
