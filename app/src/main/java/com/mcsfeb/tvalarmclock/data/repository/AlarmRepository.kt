package com.mcsfeb.tvalarmclock.data.repository

import android.content.Context
import com.mcsfeb.tvalarmclock.ui.screens.AlarmItem

/**
 * AlarmRepository - Saves and loads alarms from SharedPreferences.
 *
 * Format: "id|hour|minute|active|label" for each alarm, separated by ";;".
 * Simple and doesn't need Room database yet.
 */
class AlarmRepository(context: Context) {

    private val prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)

    fun saveAlarms(alarms: List<AlarmItem>) {
        val encoded = alarms.joinToString(";;") { alarm ->
            "${alarm.id}|${alarm.hour}|${alarm.minute}|${alarm.isActive}|${alarm.label}"
        }
        prefs.edit().putString("alarms_list", encoded).apply()
    }

    fun loadAlarms(): List<AlarmItem> {
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
}
