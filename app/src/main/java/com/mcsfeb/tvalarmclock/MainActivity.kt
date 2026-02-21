package com.mcsfeb.tvalarmclock

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.mcsfeb.tvalarmclock.data.config.DeepLinkConfig
import com.mcsfeb.tvalarmclock.data.config.DeepLinkResolver
import com.mcsfeb.tvalarmclock.data.model.AlarmItem
import com.mcsfeb.tvalarmclock.data.model.StreamingContent
import com.mcsfeb.tvalarmclock.data.repository.AlarmRepository
import com.mcsfeb.tvalarmclock.player.ContentLauncher
import com.mcsfeb.tvalarmclock.service.AlarmScheduler
import com.mcsfeb.tvalarmclock.ui.screens.AlarmActivity
import com.mcsfeb.tvalarmclock.ui.screens.AlarmSetupScreen
import com.mcsfeb.tvalarmclock.ui.screens.ContentPickerScreen
import com.mcsfeb.tvalarmclock.ui.screens.HomeScreen
import com.mcsfeb.tvalarmclock.ui.theme.TVAlarmClockTheme
import java.util.*

/**
 * MainActivity - The entry point of the TV Alarm Clock app.
 *
 * Navigation flow:
 *   HomeScreen -> AlarmSetupScreen -> ContentPickerScreen -> back to AlarmSetupScreen -> HomeScreen
 *
 * Screens:
 * - "home"            : Clock + alarm list
 * - "alarm_setup"     : Pick time + pick content (new or edit existing)
 * - "content_picker"  : Browse streaming apps, search shows, pick episodes
 */
class MainActivity : ComponentActivity() {

    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var contentLauncher: ContentLauncher
    private lateinit var alarmRepo: AlarmRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load deep link config from JSON before anything else
        DeepLinkConfig.load(this)
        DeepLinkResolver.probeAll(this)
        Log.d("MainActivity", "DeepLinkResolver: ${DeepLinkResolver.getVerifiedAppCount()} apps verified")

        alarmScheduler = AlarmScheduler(this)
        contentLauncher = ContentLauncher.getInstance(this)
        alarmRepo = AlarmRepository(this)

        setContent {
            TVAlarmClockTheme {
                var alarms by remember { mutableStateOf(alarmRepo.loadAlarms()) }
                var currentScreen by remember { mutableStateOf("home") }
                var launchResultMessage by remember { mutableStateOf<String?>(null) }

                // Alarm being edited (-1 = creating new)
                var editingAlarmId by remember { mutableIntStateOf(-1) }

                // Time for the alarm being set up
                var setupHour by remember { mutableIntStateOf(7) }
                var setupMinute by remember { mutableIntStateOf(0) }
                var setupVolume by remember { mutableIntStateOf(-1) }

                // Content chosen for the alarm being set up
                var setupContent by remember { mutableStateOf<StreamingContent?>(null) }

                val installedApps = remember { contentLauncher.getInstalledApps() }

                when (currentScreen) {
                    "home" -> {
                        HomeScreen(
                            alarms = alarms,
                            onAddAlarm = {
                                // Start fresh alarm setup
                                editingAlarmId = -1
                                setupContent = null
                                setupVolume = -1
                                currentScreen = "alarm_setup"
                            },
                            onEditAlarm = { alarm ->
                                // Edit existing alarm
                                editingAlarmId = alarm.id
                                setupHour = alarm.hour
                                setupMinute = alarm.minute
                                setupVolume = alarm.volume
                                setupContent = alarm.streamingContent
                                currentScreen = "alarm_setup"
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
                            onTestAlarm = { alarm ->
                                // Launch AlarmActivity directly for this specific alarm
                                val testIntent = Intent(this@MainActivity, AlarmActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    putExtra("ALARM_ID", alarm.id)
                                    putExtra("VOLUME", alarm.volume)
                                    alarm.streamingContent?.let { content ->
                                        putExtra("CONTENT_APP", content.app.name)
                                        putExtra("CONTENT_ID", content.contentId)
                                        putExtra("CONTENT_TITLE", content.title)
                                        putExtra("CONTENT_MODE", content.launchMode.name)
                                        putExtra("CONTENT_SEARCH_QUERY", content.searchQuery)
                                        content.seasonNumber?.let { putExtra("CONTENT_SEASON", it) }
                                        content.episodeNumber?.let { putExtra("CONTENT_EPISODE", it) }
                                    }
                                }
                                startActivity(testIntent)
                            }
                        )
                    }

                    "alarm_setup" -> {
                        AlarmSetupScreen(
                            editingHour = if (editingAlarmId > 0) setupHour else null,
                            editingMinute = if (editingAlarmId > 0) setupMinute else null,
                            editingVolume = setupVolume,
                            selectedContent = setupContent,
                            onPickContent = {
                                launchResultMessage = null
                                currentScreen = "content_picker"
                            },
                            onSave = { hour, minute, volume, content ->
                                if (editingAlarmId > 0) {
                                    // Update existing alarm
                                    alarms = alarms.map { alarm ->
                                        if (alarm.id == editingAlarmId) {
                                            val updated = alarm.copy(
                                                hour = hour,
                                                minute = minute,
                                                streamingContent = content,
                                                volume = volume,
                                                isActive = true
                                            )
                                            scheduleAlarm(updated)
                                            updated
                                        } else alarm
                                    }
                                } else {
                                    // Create new alarm
                                    val newId = (alarms.maxOfOrNull { it.id } ?: 0) + 1
                                    val newAlarm = AlarmItem(
                                        id = newId,
                                        hour = hour,
                                        minute = minute,
                                        isActive = true,
                                        streamingContent = content,
                                        volume = volume
                                    )
                                    alarms = alarms + newAlarm
                                    scheduleAlarm(newAlarm)
                                }
                                alarmRepo.saveAlarms(alarms)
                                editingAlarmId = -1
                                currentScreen = "home"
                            },
                            onBack = {
                                editingAlarmId = -1
                                currentScreen = "home"
                            }
                        )
                    }

                    "content_picker" -> {
                        ContentPickerScreen(
                            installedApps = installedApps,
                            onContentSelected = { selectedContent ->
                                setupContent = selectedContent
                                currentScreen = "alarm_setup"
                            },
                            onTestLaunch = { content ->
                                // Construct identifiers for ContentLauncher
                                val identifiers = mutableMapOf<String, String>()
                                identifiers["id"] = content.contentId
                                identifiers["title"] = content.title
                                identifiers["showName"] = content.searchQuery.ifBlank { content.title }
                                identifiers["channelName"] = content.title
                                content.seasonNumber?.let { identifiers["season"] = it.toString() }
                                content.episodeNumber?.let { identifiers["episode"] = it.toString() }
                                
                                // Determine type
                                val type = if (content.app.name == "SLING_TV") "live" else "episode"

                                contentLauncher.launchContent(content.app.packageName, type, identifiers)
                                launchResultMessage = "Launch command sent..."
                            },
                            onBack = {
                                currentScreen = "alarm_setup"
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
        Log.d("MainActivity", "Scheduled alarm ${alarm.id} for ${cal.time}")
    }
}
