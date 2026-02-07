package com.mcsfeb.tvalarmclock

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.mcsfeb.tvalarmclock.data.model.LaunchMode
import com.mcsfeb.tvalarmclock.data.model.StreamingApp
import com.mcsfeb.tvalarmclock.data.model.StreamingContent
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
 * - ContentPickerScreen: Two modes - paste a URL or just pick an app
 *
 * The selected streaming content is saved to SharedPreferences so the
 * AlarmReceiver can read it and launch the right app at alarm time.
 */
class MainActivity : ComponentActivity() {

    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var streamingLauncher: StreamingLauncher
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        alarmScheduler = AlarmScheduler(this)
        streamingLauncher = StreamingLauncher(this)
        prefs = getSharedPreferences("alarm_prefs", MODE_PRIVATE)

        setContent {
            TVAlarmClockTheme {
                // App state
                var isAlarmSet by remember { mutableStateOf(false) }
                var alarmTimeText by remember { mutableStateOf("") }
                var selectedContent by remember { mutableStateOf(loadSavedContent()) }
                var launchResultMessage by remember { mutableStateOf<String?>(null) }
                var currentScreen by remember { mutableStateOf("home") }

                val installedApps = remember { streamingLauncher.getInstalledApps() }

                when (currentScreen) {
                    "home" -> {
                        HomeScreen(
                            onSetAlarm = {
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
                            selectedAppName = selectedContent?.let {
                                "${it.app.displayName}: ${it.title}"
                            }
                        )
                    }

                    "content_picker" -> {
                        ContentPickerScreen(
                            installedApps = installedApps,
                            onContentSelected = { content ->
                                selectedContent = content
                                saveContent(content)
                                currentScreen = "home"
                            },
                            onTestLaunch = { app, contentId ->
                                val result = streamingLauncher.launch(app, contentId)
                                launchResultMessage = formatResult(result)
                            },
                            onTestLaunchAppOnly = { app ->
                                val result = streamingLauncher.launchAppOnly(app)
                                launchResultMessage = formatResult(result)
                            },
                            onBack = { currentScreen = "home" },
                            launchResultMessage = launchResultMessage
                        )
                    }
                }
            }
        }
    }

    /** Save selected content to SharedPreferences so AlarmReceiver can access it */
    private fun saveContent(content: StreamingContent) {
        prefs.edit()
            .putString("content_app", content.app.name)
            .putString("content_id", content.contentId)
            .putString("content_title", content.title)
            .putString("content_mode", content.launchMode.name)
            .apply()
    }

    /** Load previously saved content from SharedPreferences */
    private fun loadSavedContent(): StreamingContent? {
        val appName = prefs.getString("content_app", null) ?: return null
        val app = try { StreamingApp.valueOf(appName) } catch (e: Exception) { return null }
        return StreamingContent(
            app = app,
            contentId = prefs.getString("content_id", "") ?: "",
            title = prefs.getString("content_title", "") ?: "",
            launchMode = try {
                LaunchMode.valueOf(prefs.getString("content_mode", "APP_ONLY") ?: "APP_ONLY")
            } catch (e: Exception) { LaunchMode.APP_ONLY }
        )
    }

    private fun formatResult(result: LaunchResult): String {
        return when (result) {
            is LaunchResult.Success -> "✓ Launched ${result.appName}!"
            is LaunchResult.AppNotInstalled -> "✗ ${result.appName} is not installed on this TV"
            is LaunchResult.LaunchFailed -> "✗ Failed: ${result.error}"
        }
    }
}
