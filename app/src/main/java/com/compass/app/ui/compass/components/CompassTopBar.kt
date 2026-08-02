package com.compass.app.ui.compass.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButtonShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.compass.app.R
import com.compass.app.domain.model.CompassAccuracy

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CompassTopBar(accuracy: CompassAccuracy, hasSensor: Boolean, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    TopAppBar(
        modifier = modifier,
        title = { Text(stringResource(R.string.title_compass)) },
        subtitle = {
            AccuracyChip(
                accuracy = accuracy,
                hasSensor = hasSensor,
                modifier = Modifier.padding(top = 6.dp),
            )
        },
        actions = {
            CompassTopBarActions(onSettings = onSettings)
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CompassTopBarActions(onSettings: () -> Unit) {
    // Single emitter for compose-rules MultipleEmitters (button + trailing spacer).
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledIconButton(
            onClick = onSettings,
            shapes = IconButtonShapes(
                shape = IconButtonDefaults.largeRoundShape,
                pressedShape = IconButtonDefaults.largePressedShape,
            ),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_settings_24),
                contentDescription = stringResource(R.string.action_settings),
            )
        }
        Spacer(Modifier.width(8.dp))
    }
}
