package com.mcsfeb.tvalarmclock

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.mcsfeb.tvalarmclock.data.model.StreamingApp
import com.mcsfeb.tvalarmclock.player.LaunchResult
import com.mcsfeb.tvalarmclock.player.StreamingLauncher
import com.mcsfeb.tvalarmclock.service.AlarmScheduler
import com.mcsfeb.tvalarmclock.ui.screens.ContentPickerScreen
import com.mcsfeb.tvalarmclock.ui.screens.HomeScreen
import com.mcsfeb.tvalarmclock.ui.theme.TVAlarmClockTheme
import java.text.SimpleDateFormat
import java.util.*

/**
 * MainActivity - The entry point of the TV Alarm Clock app.
 *
 * Handles navigation between:
 * - HomeScreen: Main clock + alarm controls + streaming app status
 * - ContentPickerScreen: Browse and select streaming apps, test deep links
 *
 * For Milestone 2, we added:
 * - StreamingLauncher for deep-linking into 18 streaming apps
 * - ContentPickerScreen for picking apps and testing launches
 * - Streaming app selection shown on the home screen
 */
class MainActivity : ComponentActivity() {

    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var streamingLauncher: StreamingLauncher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        alarmScheduler = AlarmScheduler(this)
        streamingLauncher = StreamingLauncher(this)

        setContent {
            TVAlarmClockTheme {
                // App state
                var isAlarmSet by remember { mutableStateOf(false) }
                var alarmTimeText by remember { mutableStateOf("") }
                var selectedApp by remember { mutableStateOf<StreamingApp?>(null) }
                var selectedContentId by remember { mutableStateOf("") }
                var launchResultMessage by remember { mutableStateOf<String?>(null) }

                // Navigation: which screen are we on?
                var currentScreen by remember { mutableStateOf("home") }

                // Get the list of installed streaming apps
                val installedApps = remember {
                    streamingLauncher.getInstalledApps()
                }

                when (currentScreen) {
                    "home" -> {
                        HomeScreen(
                            onSetAlarm = {
                                // Set an alarm 30 seconds from now (for easy testing)
                                val triggerTime = System.currentTimeMillis() + 30_000
                                alarmScheduler.schedule(triggerTime, alarmId = 0)

                                val formatter = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
                                alarmTimeText = formatter.format(Date(triggerTime))
                                isAlarmSet = true
                            },
                            onCancelAlarm = {
                                alarmScheduler.cancel(alarmId = 0)
                                isAlarmSet = false
                                alarmTimeText = ""
                            },
                            onPickStreamingApp = {
                                launchResultMessage = null
                                currentScreen = "content_picker"
                            },
                            isAlarmSet = isAlarmSet,
                            alarmTimeText = alarmTimeText,
                            selectedAppName = selectedApp?.displayName
                        )
                    }

                    "content_picker" -> {
                        ContentPickerScreen(
                            installedApps = installedApps,
                            onLaunchApp = { app, contentId ->
                                selectedApp = app
                                selectedContentId = contentId

                                // Try to launch the deep link
                                val result = streamingLauncher.launch(app, contentId)
                                launchResultMessage = when (result) {
                                    is LaunchResult.Success ->
                                        "✓ Launched ${result.appName}!"
                                    is LaunchResult.AppNotInstalled ->
                                        "✗ ${result.appName} is not installed on this TV"
                                    is LaunchResult.LaunchFailed ->
                                        "✗ Failed to launch ${result.appName}: ${result.error}"
                                }
                            },
                            onLaunchAppOnly = { app ->
                                selectedApp = app

                                val result = streamingLauncher.launchAppOnly(app)
                                launchResultMessage = when (result) {
                                    is LaunchResult.Success ->
                                        "✓ Opened ${result.appName}!"
                                    is LaunchResult.AppNotInstalled ->
                                        "✗ ${result.appName} is not installed on this TV"
                                    is LaunchResult.LaunchFailed ->
                                        "✗ Failed to open ${result.appName}: ${result.error}"
                                }
                            },
                            onBack = {
                                currentScreen = "home"
                            },
                            launchResultMessage = launchResultMessage
                        )
                    }
                }
            }
        }
    }
}
