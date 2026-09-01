package com.compass.app.ui.update

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.compass.app.R

/**
 * Compat first-open prompt: this Android 17 device can switch to the
 * future flavor. Hosted by MainActivity; a no-op when [visible] is false.
 *
 * No = stay on compat. Not now = next version bump installs future.
 * OK = download the future APK now.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FutureUpgradeDialog(visible: Boolean, onNo: () -> Unit, onNotNow: () -> Unit, onOk: () -> Unit) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text(stringResource(R.string.future_upgrade_title)) },
        text = { Text(stringResource(R.string.future_upgrade_text)) },
        confirmButton = {
            TextButton(onClick = onOk, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.common_action_ok))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onNo, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.future_upgrade_no))
                }
                TextButton(onClick = onNotNow, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.future_upgrade_not_now))
                }
            }
        },
    )
}
