package app.referencecanvas.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.referencecanvas.BuildConfig
import app.referencecanvas.R
import app.referencecanvas.core.design.RcTheme
import app.referencecanvas.core.design.color
import app.referencecanvas.core.design.labelRes
import app.referencecanvas.domain.model.AccentOption
import app.referencecanvas.domain.model.BoardViewMode
import app.referencecanvas.domain.model.GalleryDensity
import app.referencecanvas.domain.model.ThemeMode
import app.referencecanvas.ui.components.ScreenHeader

@Composable
fun SettingsScreen(
    onOpenTrash: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    backupViewModel: BackupViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val colors = RcTheme.colors

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenHeader(stringResource(R.string.nav_settings))

        Section(R.string.settings_appearance) {
            Label(R.string.settings_accent)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AccentOption.entries.forEach { option ->
                    AccentSwatch(option, selected = option == settings.accent) { viewModel.setAccent(option) }
                }
            }
            Label(R.string.settings_theme)
            OptionRow(
                options = ThemeMode.entries,
                selected = settings.themeMode,
                label = {
                    stringResource(
                        when (it) {
                            ThemeMode.Dark -> R.string.theme_dark
                            ThemeMode.Light -> R.string.theme_light
                            ThemeMode.System -> R.string.theme_system
                        },
                    )
                },
                onSelect = viewModel::setThemeMode,
            )
        }

        Section(R.string.settings_boards) {
            Label(R.string.settings_default_view)
            OptionRow(
                options = BoardViewMode.entries,
                selected = settings.defaultViewMode,
                label = {
                    stringResource(if (it == BoardViewMode.Canvas) R.string.view_canvas else R.string.view_gallery)
                },
                onSelect = viewModel::setDefaultViewMode,
            )
            Label(R.string.settings_gallery_density)
            OptionRow(
                options = GalleryDensity.entries,
                selected = settings.galleryDensity,
                label = {
                    stringResource(
                        when (it) {
                            GalleryDensity.Compact -> R.string.density_compact
                            GalleryDensity.Comfortable -> R.string.density_comfortable
                            GalleryDensity.Large -> R.string.density_large
                        },
                    )
                },
                onSelect = viewModel::setGalleryDensity,
            )
            Hint(R.string.settings_density_desc)
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_snap), color = colors.textPrimary, style = MaterialTheme.typography.bodyLarge)
                    Hint(R.string.settings_snap_desc)
                }
                Switch(
                    checked = settings.snapToGrid,
                    onCheckedChange = viewModel::setSnapToGrid,
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = colors.onAccent),
                )
            }
        }

        Section(R.string.settings_backup) { BackupSection(backupViewModel) }

        Section(R.string.settings_storage) { StorageSection(backupViewModel, onOpenTrash) }

        Section(R.string.settings_privacy) {
            Text(stringResource(R.string.settings_privacy_body), color = colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
        }

        Section(R.string.settings_about) {
            Text(
                stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                color = colors.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            DiagnosticsSection(backupViewModel)
        }
    }
}

@Composable
internal fun Section(@StringRes titleRes: Int, content: @Composable ColumnScope.() -> Unit) {
    val colors = RcTheme.colors
    Text(
        stringResource(titleRes),
        style = MaterialTheme.typography.labelLarge,
        color = colors.accent,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
    Surface(
        shape = MaterialTheme.shapes.large,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
internal fun Label(@StringRes res: Int) {
    Text(stringResource(res), color = RcTheme.colors.textPrimary, style = MaterialTheme.typography.bodyLarge)
}

@Composable
internal fun Hint(@StringRes res: Int) {
    Text(stringResource(res), color = RcTheme.colors.textMuted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AccentSwatch(option: AccentOption, selected: Boolean, onClick: () -> Unit) {
    val colors = RcTheme.colors
    val name = stringResource(option.labelRes())
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(option.color())
            .then(if (selected) Modifier.border(3.dp, colors.textPrimary, CircleShape) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onAccent)
    }
}

@Composable
private fun <T> OptionRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    val colors = RcTheme.colors
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = colors.accent.copy(alpha = 0.18f),
                    activeContentColor = colors.textPrimary,
                    activeBorderColor = colors.accent,
                ),
            ) { Text(label(option)) }
        }
    }
}
