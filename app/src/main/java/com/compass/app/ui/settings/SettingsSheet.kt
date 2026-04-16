package com.compass.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.compass.app.R
import com.compass.app.data.preferences.Responsiveness
import com.compass.app.data.preferences.ThemeMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsSheet(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    oledBlack: Boolean,
    trueNorth: Boolean,
    responsiveness: Responsiveness,
    onThemeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onOledBlackChange: (Boolean) -> Unit,
    onTrueNorthChange: (Boolean) -> Unit,
    onResponsivenessChange: (Responsiveness) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = LocalHapticFeedback.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Inherit the Expressive sheet shape token rather than hard-coding 28dp.
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            SectionLabel(stringResource(R.string.settings_theme_label))
            // Connected M3 Expressive segmented selector — ToggleButtons with
            // connectedLeading/Middle/TrailingButtonShapes, which morphs the selected
            // button to a rounded pill while neighbours compress (this is the
            // Expressive refresh of the classic SingleChoiceSegmentedButtonRow).
            val themeOptions = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
            val selectedIndex = themeOptions.indexOf(themeMode)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                themeOptions.forEachIndexed { index, mode ->
                    val labelRes = when (mode) {
                        ThemeMode.SYSTEM -> R.string.settings_theme_system
                        ThemeMode.LIGHT -> R.string.settings_theme_light
                        ThemeMode.DARK -> R.string.settings_theme_dark
                    }
                    val shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        themeOptions.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    }
                    ToggleButton(
                        checked = index == selectedIndex,
                        onCheckedChange = { checked ->
                            if (checked) {
                                haptics.tick()
                                onThemeChange(mode)
                            }
                        },
                        shapes = shapes,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = stringResource(labelRes))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            SectionLabel(stringResource(R.string.settings_responsiveness_label))
            // Same connected-ToggleButton pattern as the theme picker — M3E segmented.
            val responsivenessOptions = Responsiveness.entries
            val respSelectedIndex = responsivenessOptions.indexOf(responsiveness)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                responsivenessOptions.forEachIndexed { index, mode ->
                    val labelRes = when (mode) {
                        Responsiveness.SLOWEST -> R.string.settings_responsiveness_slowest
                        Responsiveness.SLOW -> R.string.settings_responsiveness_slow
                        Responsiveness.NORMAL -> R.string.settings_responsiveness_normal
                        Responsiveness.FAST -> R.string.settings_responsiveness_fast
                        Responsiveness.FASTEST -> R.string.settings_responsiveness_fastest
                    }
                    val shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        responsivenessOptions.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    }
                    ToggleButton(
                        checked = index == respSelectedIndex,
                        onCheckedChange = { checked ->
                            if (checked) {
                                haptics.tick()
                                onResponsivenessChange(mode)
                            }
                        },
                        shapes = shapes,
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = stringResource(labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))

            ToggleRow(
                titleRes = R.string.settings_dynamic_color,
                subtitleRes = R.string.settings_dynamic_color_desc,
                checked = dynamicColor,
                onCheckedChange = { haptics.tick(); onDynamicColorChange(it) },
            )
            ToggleRow(
                titleRes = R.string.settings_oled_dark,
                subtitleRes = R.string.settings_oled_dark_desc,
                checked = oledBlack,
                onCheckedChange = { haptics.tick(); onOledBlackChange(it) },
            )
            ToggleRow(
                titleRes = R.string.settings_true_north,
                subtitleRes = R.string.settings_true_north_desc,
                checked = trueNorth,
                onCheckedChange = { haptics.tick(); onTrueNorthChange(it) },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun HapticFeedback.tick() {
    performHapticFeedback(HapticFeedbackType.ContextClick)
}

@Composable
private fun SectionLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun ToggleRow(
    @StringRes titleRes: Int,
    @StringRes subtitleRes: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(subtitleRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
