package com.mcsfeb.tvalarmclock

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.mcsfeb.tvalarmclock.data.model.LaunchMode
import com.mcsfeb.tvalarmclock.data.model.StreamingApp
import com.mcsfeb.tvalarmclock.data.model.StreamingContent
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
 * Handles navigation between:
 * - HomeScreen: Main clock + alarm list + time picker + streaming app status
 * - ContentPickerScreen: Browse channels, search shows, or manual entry
 *
 * Supports MULTIPLE alarms - each gets its own AlarmManager entry with a unique ID.
 * Also has a "Test Alarm" button that fires the alarm immediately for testing.
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
                var alarms by remember { mutableStateOf(loadAlarms()) }
                var selectedContent by remember { mutableStateOf(loadSavedContent()) }
                var launchResultMessage by remember { mutableStateOf<String?>(null) }
                var currentScreen by remember { mutableStateOf("home") }

                val installedApps = remember { streamingLauncher.getInstalledApps() }

                when (currentScreen) {
                    "home" -> {
                        HomeScreen(
                            alarms = alarms,
                            onAddAlarm = { hour, minute ->
                                // Create a new alarm with a unique ID
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
                                saveAlarms(alarms)
                                scheduleAlarm(newAlarm)
                            },
                            onDeleteAlarm = { alarm ->
                                alarmScheduler.cancel(alarm.id)
                                alarms = alarms.filter { it.id != alarm.id }
                                saveAlarms(alarms)
                            },
                            onToggleAlarm = { alarm ->
                                val updated = alarm.copy(isActive = !alarm.isActive)
                                alarms = alarms.map { if (it.id == alarm.id) updated else it }
                                saveAlarms(alarms)
                                if (updated.isActive) {
                                    scheduleAlarm(updated)
                                } else {
                                    alarmScheduler.cancel(alarm.id)
                                }
                            },
                            onTestAlarm = {
                                // Fire alarm immediately (2 seconds from now for reliability)
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
                                saveContent(content)
                                // Update labels on all existing alarms
                                val label = "${content.app.displayName}: ${content.title}"
                                alarms = alarms.map { it.copy(label = label) }
                                saveAlarms(alarms)
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

    /** Schedule an alarm using AlarmManager for the next occurrence of the given time */
    private fun scheduleAlarm(alarm: AlarmItem) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // If the time has already passed today, schedule for tomorrow
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        alarmScheduler.schedule(cal.timeInMillis, alarmId = alarm.id)
    }

    /** Save selected content to SharedPreferences so AlarmReceiver can access it */
    private fun saveContent(content: StreamingContent) {
        prefs.edit()
            .putString("content_app", content.app.name)
            .putString("content_id", content.contentId)
            .putString("content_title", content.title)
            .putString("content_mode", content.launchMode.name)
            .putString("content_search_query", content.searchQuery)
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
            } catch (e: Exception) { LaunchMode.APP_ONLY },
            searchQuery = prefs.getString("content_search_query", "") ?: ""
        )
    }

    /**
     * Save the alarm list to SharedPreferences.
     *
     * Format: "id|hour|minute|active|label" for each alarm, separated by ";;".
     * Simple and doesn't need Room database yet.
     */
    private fun saveAlarms(alarms: List<AlarmItem>) {
        val encoded = alarms.joinToString(";;") { alarm ->
            "${alarm.id}|${alarm.hour}|${alarm.minute}|${alarm.isActive}|${alarm.label}"
        }
        prefs.edit().putString("alarms_list", encoded).apply()
    }

    /** Load alarms from SharedPreferences */
    private fun loadAlarms(): List<AlarmItem> {
        val encoded = prefs.getString("alarms_list", null) ?: return emptyList()
        if (encoded.isBlank()) return emptyList()
        return try {
            encoded.split(";;").mapNotNull { entry ->
                val parts = entry.split("|")
                if (parts.size >= 4) {
                    AlarmItem(
                        id = parts[0].toInt(),
                        hour = parts[1].toInt(),
                        minute = parts[2].toInt(),
                        isActive = parts[3].toBoolean(),
                        label = if (parts.size >= 5) parts[4] else ""
                    )
                } else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun formatResult(result: LaunchResult): String {
        return when (result) {
            is LaunchResult.Success -> "\u2713 Launched ${result.appName}!"
            is LaunchResult.AppNotInstalled -> "\u2717 ${result.appName} is not installed on this TV"
            is LaunchResult.LaunchFailed -> "\u2717 Failed: ${result.error}"
        }
    }
}
