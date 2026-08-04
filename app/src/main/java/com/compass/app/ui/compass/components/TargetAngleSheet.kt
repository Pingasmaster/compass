package com.compass.app.ui.compass.components

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.compass.app.R
import com.compass.app.domain.model.toCardinal
import com.compass.app.ui.compass.normalizeBearingDegrees

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TargetAngleSheet(currentTarget: Float?, onConfirm: (Float?) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    // rememberSaveable so an in-progress (unconfirmed) bearing survives the
    // activity recreation that rotating into the dual-pane layout triggers.
    val sliderState = rememberSaveable(saver = TargetSliderStateSaver) {
        SliderState(
            value = currentTarget?.coerceIn(0f, 360f) ?: 0f,
            valueRange = 0f..360f,
        )
    }
    val bearing = sliderState.value

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.target_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.target_sheet_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            TargetBearingHeader(bearing = bearing)
            Spacer(Modifier.height(12.dp))
            TargetBearingField(sliderState = sliderState)
            Spacer(Modifier.height(12.dp))
            TargetBearingSlider(sliderState = sliderState)
            Spacer(Modifier.height(24.dp))
            TargetSheetActions(
                onClear = { onConfirm(null) },
                onCancel = onDismiss,
                onSet = { onConfirm(normalizeBearingDegrees(sliderState.value)) },
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TargetBearingHeader(bearing: Float) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "${bearing.toInt()}",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            text = " deg  ${bearing.toCardinal()}",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
        )
    }
}

@Composable
private fun TargetBearingField(sliderState: SliderState) {
    // Numpad text entry - bidirectional with the slider; the field only drives the
    // slider while focused, and the slider only drives the field while unfocused.
    // On blur we snap the field back to the slider so out-of-range or empty typed
    // values can't outlive focus and mislead the Set button.
    val focusManager = LocalFocusManager.current
    val sliderValueInt by remember {
        derivedStateOf { sliderState.value.toInt() }
    }
    var fieldFocused by remember { mutableStateOf(false) }
    var fieldText by rememberSaveable { mutableStateOf(sliderValueInt.toString()) }
    LaunchedEffect(sliderValueInt) {
        if (!fieldFocused) fieldText = sliderValueInt.toString()
    }
    OutlinedTextField(
        value = fieldText,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(3)
            fieldText = digits
            digits.toIntOrNull()?.let { v ->
                if (v in 0..360) sliderState.value = v.toFloat()
            }
        },
        singleLine = true,
        label = { Text(stringResource(R.string.target_sheet_field_label)) },
        suffix = { Text(" deg", style = MaterialTheme.typography.titleLarge) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                val wasFocused = fieldFocused
                fieldFocused = focusState.isFocused
                if (wasFocused && !focusState.isFocused) {
                    fieldText = sliderValueInt.toString()
                }
            },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
private val TargetSliderStateSaver = Saver<SliderState, Float>(
    save = { it.value },
    restore = { saved -> SliderState(value = saved, valueRange = 0f..360f) },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetBearingSlider(sliderState: SliderState) {
    val interactionSource = remember { MutableInteractionSource() }
    val tooltipState = rememberTooltipState(isPersistent = true)
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start,
                is PressInteraction.Press,
                -> tooltipState.show()

                is DragInteraction.Stop,
                is DragInteraction.Cancel,
                is PressInteraction.Release,
                is PressInteraction.Cancel,
                -> tooltipState.dismiss()
            }
        }
    }
    val tooltipValue by remember {
        derivedStateOf { sliderState.value.toInt() }
    }

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            TooltipAnchorPosition.Above,
        ),
        tooltip = {
            PlainTooltip {
                Text(stringResource(R.string.target_sheet_value_label, tooltipValue))
            }
        },
        state = tooltipState,
        focusable = false,
        enableUserInput = false,
    ) {
        Slider(
            state = sliderState,
            interactionSource = interactionSource,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.tertiary,
                activeTrackColor = MaterialTheme.colorScheme.tertiary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW", "N").forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TargetSheetActions(onClear: () -> Unit, onCancel: () -> Unit, onSet: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(
            shapes = ButtonDefaults.shapes(),
            onClick = onClear,
        ) { Text(stringResource(R.string.target_sheet_clear)) }
        Spacer(Modifier.width(8.dp))
        TextButton(
            shapes = ButtonDefaults.shapes(),
            onClick = onCancel,
        ) { Text(stringResource(R.string.target_sheet_cancel)) }
        Spacer(Modifier.width(8.dp))
        Button(
            shapes = ButtonDefaults.shapes(),
            onClick = onSet,
        ) { Text(stringResource(R.string.target_sheet_set)) }
    }
}
