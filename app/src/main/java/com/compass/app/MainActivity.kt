package com.compass.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.compass.app.data.preferences.ThemeMode
import com.compass.app.ui.compass.CompassScreen
import com.compass.app.ui.theme.CompassTheme
import com.compass.app.ui.update.AppUpdateDialog
import com.compass.app.ui.update.FutureUpgradeDialog
import com.compass.app.update.UpdateUiState

class MainActivity : ComponentActivity() {

    private val appUpdateController
        get() = (application as CompassApplication).appUpdateController

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        val prefs = (application as CompassApplication).userPreferences
        val controller = appUpdateController

        setContent {
            val themeMode by prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val dynamicColor by prefs.dynamicColorEnabled.collectAsStateWithLifecycle(initialValue = true)
            val oledBlack by prefs.oledBlackEnabled.collectAsStateWithLifecycle(initialValue = false)

            val isDark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            // Re-apply edge-to-edge whenever dark mode flips so the system bar
            // icon colour tracks the theme. enableEdgeToEdge is idempotent and
            // has no teardown, so LaunchedEffect(isDark) is the right shape -
            // reserving DisposableEffect for effects that actually need cleanup.
            LaunchedEffect(isDark) {
                val style = if (isDark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            val updateState by controller.state.collectAsStateWithLifecycle()
            val showFutureUpgrade by controller.showFutureUpgradePrompt.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }
            LaunchedEffect(controller) {
                controller.maybeOfferFutureUpgrade()
            }
            LaunchedEffect(controller) {
                controller.messages.collect { resId ->
                    snackbarHostState.showSnackbar(getString(resId))
                }
            }

            CompassTheme(
                darkTheme = isDark,
                dynamicColor = dynamicColor,
                oledBlack = oledBlack,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    CompassScreen(isDark = isDark)
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }

                AppUpdateDialog(
                    state = updateState,
                    onConfirmDownload = { controller.confirmDownload() },
                    onDismiss = { controller.dismiss() },
                )

                val updateBusy = updateState !is UpdateUiState.Idle
                FutureUpgradeDialog(
                    visible = showFutureUpgrade && !updateBusy,
                    onNo = { controller.declineFutureUpgrade() },
                    onNotNow = { controller.deferFutureUpgrade() },
                    onOk = { controller.upgradeToFutureNow() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appUpdateController.retryPendingInstallIfReady()
    }
}
