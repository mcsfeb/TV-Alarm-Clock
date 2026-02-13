package com.mcsfeb.tvalarmclock

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.mcsfeb.tvalarmclock.data.config.DeepLinkConfig
import com.mcsfeb.tvalarmclock.data.config.DeepLinkResolver
import com.mcsfeb.tvalarmclock.data.model.AlarmItem
import com.mcsfeb.tvalarmclock.data.model.StreamingContent
import com.mcsfeb.tvalarmclock.data.repository.AlarmRepository
import com.mcsfeb.tvalarmclock.data.repository.ContentRepository
import com.mcsfeb.tvalarmclock.player.LaunchResult
import com.mcsfeb.tvalarmclock.player.ProfileAutoSelector
import com.mcsfeb.tvalarmclock.player.StreamingLauncher
import com.mcsfeb.tvalarmclock.service.AlarmScheduler
import com.mcsfeb.tvalarmclock.ui.screens.ContentPickerScreen
import com.mcsfeb.tvalarmclock.ui.screens.HomeScreen
import com.mcsfeb.tvalarmclock.ui.theme.TVAlarmClockTheme
import java.util.*

/**
 * MainActivity - The entry point of the TV Alarm Clock app.
 *
 * Handles navigation between HomeScreen and ContentPickerScreen.
 * Supports two content picker flows:
 *   1. Picking content for the NEXT new alarm (contentForNewAlarm)
 *   2. Editing content for an EXISTING alarm (editingAlarmId)
 */
class MainActivity : ComponentActivity() {

    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var streamingLauncher: StreamingLauncher
    private lateinit var alarmRepo: AlarmRepository
    private lateinit var contentRepo: ContentRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load deep link config from JSON before anything else
        DeepLinkConfig.load(this)

        // Probe all installed streaming apps to discover working deep link formats
        DeepLinkResolver.probeAll(this)
        Log.d("MainActivity", "DeepLinkResolver: ${DeepLinkResolver.getVerifiedAppCount()} apps verified, ${DeepLinkResolver.getTotalVerifiedFormats()} total formats")

        alarmScheduler = AlarmScheduler(this)
        streamingLauncher = StreamingLauncher(this)
        alarmRepo = AlarmRepository(this)
        contentRepo = ContentRepository(this)

        setContent {
            TVAlarmClockTheme {
                var alarms by remember { mutableStateOf(alarmRepo.loadAlarms()) }
                var contentForNewAlarm by remember { mutableStateOf<StreamingContent?>(contentRepo.loadContent()) }
                var launchResultMessage by remember { mutableStateOf<String?>(null) }
                var currentScreen by remember { mutableStateOf("home") }

                // Track which alarm we're editing (null = picking for new alarm)
                var editingAlarmId by remember { mutableIntStateOf(-1) }

                val installedApps = remember { streamingLauncher.getInstalledApps() }

                when (currentScreen) {
                    "home" -> {
                        HomeScreen(
                            alarms = alarms,
                            onAddAlarm = { hour, minute, content ->
                                val newId = (alarms.maxOfOrNull { it.id } ?: 0) + 1
                                val newAlarm = AlarmItem(
                                    id = newId,
                                    hour = hour,
                                    minute = minute,
                                    isActive = true,
                                    streamingContent = content
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
                                editingAlarmId = -1  // Not editing, picking for new alarm
                                currentScreen = "content_picker"
                            },
                            onOpenAccessibilitySettings = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                            isAccessibilityEnabled = ProfileAutoSelector.isServiceEnabled(),
                            contentForNewAlarm = contentForNewAlarm,
                            onContentForNewAlarmSelected = {
                                contentForNewAlarm = it
                                contentRepo.saveContent(it)
                                currentScreen = "home"
                            },
                            onEditAlarmContent = { alarm ->
                                // User tapped edit on an existing alarm - go to content picker
                                launchResultMessage = null
                                editingAlarmId = alarm.id
                                currentScreen = "content_picker"
                            }
                        )
                    }

                    "content_picker" -> {
                        ContentPickerScreen(
                            installedApps = installedApps,
                            onContentSelected = { selectedContent ->
                                if (editingAlarmId > 0) {
                                    // Update the existing alarm's content
                                    alarms = alarms.map { alarm ->
                                        if (alarm.id == editingAlarmId) {
                                            alarm.copy(streamingContent = selectedContent)
                                        } else alarm
                                    }
                                    alarmRepo.saveAlarms(alarms)
                                    editingAlarmId = -1
                                } else {
                                    // Update the default content for new alarms
                                    contentForNewAlarm = selectedContent
                                    contentRepo.saveContent(selectedContent)
                                }
                                currentScreen = "home"
                            },
                            onTestLaunch = { content ->
                                val result = streamingLauncher.launch(content)
                                launchResultMessage = formatResult(result)
                            },
                            onBack = {
                                editingAlarmId = -1
                                currentScreen = "home"
                            },
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

    private fun formatResult(result: LaunchResult): String = result.displayMessage()
}
