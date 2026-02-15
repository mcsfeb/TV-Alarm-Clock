package com.mcsfeb.tvalarmclock.data.repository

import android.content.Context
import android.util.Log
import com.mcsfeb.tvalarmclock.data.model.AlarmItem
import com.mcsfeb.tvalarmclock.data.model.LaunchMode
import com.mcsfeb.tvalarmclock.data.model.StreamingApp
import com.mcsfeb.tvalarmclock.data.model.StreamingContent

/**
 * AlarmRepository - Saves and loads alarms from SharedPreferences.
 *
 * Format: "id|hour|minute|active|hasContent|appOrdinal|contentId|title|launchModeOrdinal|searchQuery|season|episode"
 * (hasContent and subsequent fields are optional)
 */
class AlarmRepository(context: Context) {

    private val prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)

    fun saveAlarms(alarms: List<AlarmItem>) {
        val encoded = alarms.joinToString(";;") { alarm ->
            val base = "${alarm.id}|${alarm.hour}|${alarm.minute}|${alarm.isActive}"
            alarm.streamingContent?.let { content ->
                val s = content.seasonNumber?.toString() ?: ""
                val e = content.episodeNumber?.toString() ?: ""
                "$base|true|${content.app.ordinal}|${content.contentId}|${content.title}|${content.launchMode.ordinal}|${content.searchQuery}|$s|$e"
            } ?: "$base|false"
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
                    val id = parts[0].toInt()
                    val hour = parts[1].toInt()
                    val minute = parts[2].toInt()
                    val isActive = parts[3].toBoolean()

                    val streamingContent = if (parts.size >= 5 && parts[4].toBoolean()) {
                        // Content exists
                        if (parts.size >= 10) { // Check for minimum required parts for content
                            val app = StreamingApp.entries[parts[5].toInt()]
                            val contentId = parts[6]
                            val title = parts[7]
                            val launchMode = LaunchMode.entries[parts[8].toInt()]
                            val searchQuery = parts[9]
                            
                            val season = if (parts.size > 10 && parts[10].isNotBlank()) parts[10].toIntOrNull() else null
                            val episode = if (parts.size > 11 && parts[11].isNotBlank()) parts[11].toIntOrNull() else null

                            StreamingContent(app, contentId, title, launchMode, searchQuery, season, episode)
                        } else {
                            // Malformed content data, return null for streamingContent
                            null
                        }
                    } else {
                        null // No content or hasContent is false
                    }

                    AlarmItem(id, hour, minute, isActive, streamingContent)
                } else null
            }
        } catch (e: Exception) {
            Log.e("AlarmRepository", "Error loading alarms", e)
            emptyList()
        }
    }
}
