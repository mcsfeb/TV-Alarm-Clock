package com.mcsfeb.tvalarmclock

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.mcsfeb.tvalarmclock.data.repository.AlarmRepository
import com.mcsfeb.tvalarmclock.data.repository.ContentRepository
import com.mcsfeb.tvalarmclock.player.LaunchResult
import com.mcsfeb.tvalarmclock.player.ProfileAutoSelector
import com.mcsfeb.tvalarmclock.player.StreamingLauncher
import com.mcsfeb.tvalarmclock.service.AlarmScheduler
import com.mcsfeb.tvalarmclock.ui.screens.AlarmItem
import com.mcsfeb.tvalarmclock.ui.screens.ContentPickerScreen
import com.mcsfeb.tvalarmclock.ui.screens.HomeScreen
import com.mcsfeb.tvalarmclock.ui.theme.TVAlarmClockTheme
import java.util.*

/**
 * MainActivity - The entry point of the TV Alarm Clock app.
 *
 * Handles navigation between HomeScreen and ContentPickerScreen.
 * Uses AlarmRepository and ContentRepository for persistence.
 */
class MainActivity : ComponentActivity() {

    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var streamingLauncher: StreamingLauncher
    private lateinit var alarmRepo: AlarmRepository
    private lateinit var contentRepo: ContentRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        alarmScheduler = AlarmScheduler(this)
        streamingLauncher = StreamingLauncher(this)
        alarmRepo = AlarmRepository(this)
        contentRepo = ContentRepository(this)

        setContent {
            TVAlarmClockTheme {
                var alarms by remember { mutableStateOf(alarmRepo.loadAlarms()) }
                var selectedContent by remember { mutableStateOf(contentRepo.loadContent()) }
                var launchResultMessage by remember { mutableStateOf<String?>(null) }
                var currentScreen by remember { mutableStateOf("home") }

                val installedApps = remember { streamingLauncher.getInstalledApps() }

                when (currentScreen) {
                    "home" -> {
                        HomeScreen(
                            alarms = alarms,
                            onAddAlarm = { hour, minute ->
                                val newId = (alarms.maxOfOrNull { it.id } ?: 0) + 1
                                val contentLabel = selectedContent?.let {
                                    "${it.app.displayName}: ${it.title}"
                                } ?: ""
                                val newAlarm = AlarmItem(
                                    id = newId,
                                    hour = hour,
                                    minute = minute,
                                    isActive = true,
                                    label = contentLabel
                                )
                                alarms = alarms + newAlarm
                                alarmRepo.saveAlarms(alarms)
                                scheduleAlarm(newAlarm)
                            },
                            onDeleteAlarm = { alarm ->
                                alarmScheduler.cancel(alarm.id)
                                alarms = alarms.filter { it.id != alarm.id }
                                alarmRepo.saveAlarms(alarms)
                            },
                            onToggleAlarm = { alarm ->
                                val updated = alarm.copy(isActive = !alarm.isActive)
                                alarms = alarms.map { if (it.id == alarm.id) updated else it }
                                alarmRepo.saveAlarms(alarms)
                                if (updated.isActive) {
                                    scheduleAlarm(updated)
                                } else {
                                    alarmScheduler.cancel(alarm.id)
                                }
                            },
                            onTestAlarm = {
                                val triggerTime = System.currentTimeMillis() + 2_000
                                alarmScheduler.schedule(triggerTime, alarmId = 9999)
                            },
                            onPickStreamingApp = {
                                launchResultMessage = null
                                currentScreen = "content_picker"
                            },
                            onOpenAccessibilitySettings = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                            isAccessibilityEnabled = ProfileAutoSelector.isServiceEnabled(),
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
                                contentRepo.saveContent(content)
                                val label = "${content.app.displayName}: ${content.title}"
                                alarms = alarms.map { it.copy(label = label) }
                                alarmRepo.saveAlarms(alarms)
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

    private fun scheduleAlarm(alarm: AlarmItem) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        alarmScheduler.schedule(cal.timeInMillis, alarmId = alarm.id)
    }

    private fun formatResult(result: LaunchResult): String {
        return when (result) {
            is LaunchResult.Success -> "\u2713 Launched ${result.appName}!"
            is LaunchResult.AppNotInstalled -> "\u2717 ${result.appName} is not installed on this TV"
            is LaunchResult.LaunchFailed -> "\u2717 Failed: ${result.error}"
        }
    }
}
