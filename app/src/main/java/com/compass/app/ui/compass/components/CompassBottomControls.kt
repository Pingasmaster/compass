package com.compass.app.ui.compass.components

import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.compass.app.R

@Composable
fun CompassTargetFab(targetActive: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = if (targetActive) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        contentColor = if (targetActive) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        },
        shape = FloatingActionButtonDefaults.shape,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_gps_fixed_24),
            contentDescription = stringResource(R.string.action_set_target_angle),
        )
    }
}
