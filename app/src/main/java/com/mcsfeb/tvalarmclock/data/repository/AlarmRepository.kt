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
 * Format: "id|hour|minute|active|volume|hasContent|appOrdinal|contentId|title|launchModeOrdinal|searchQuery|season|episode"
 * Volume field added in v2 (Feb 2026). Old alarms without volume default to -1 (unchanged).
 */
class AlarmRepository(context: Context) {

    private val prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)

    fun saveAlarms(alarms: List<AlarmItem>) {
        val encoded = alarms.joinToString(";;") { alarm ->
            val base = "${alarm.id}|${alarm.hour}|${alarm.minute}|${alarm.isActive}|${alarm.volume}"
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

                    // v2 format has volume at index 4, hasContent at index 5
                    // v1 format has hasContent at index 4
                    // Detect by checking: in v2, parts[4] is a number (-1 to 100)
                    // In v1, parts[4] is "true" or "false"
                    val isV2 = parts.size >= 5 && parts[4] != "true" && parts[4] != "false"

                    val volume: Int
                    val contentStartIndex: Int

                    if (isV2) {
                        volume = parts[4].toIntOrNull() ?: -1
                        contentStartIndex = 5
                    } else {
                        volume = -1
                        contentStartIndex = 4
                    }

                    val streamingContent = if (parts.size > contentStartIndex && parts[contentStartIndex].toBoolean()) {
                        val dataStart = contentStartIndex + 1
                        if (parts.size >= dataStart + 5) {
                            val app = StreamingApp.entries[parts[dataStart].toInt()]
                            val contentId = parts[dataStart + 1]
                            val title = parts[dataStart + 2]
                            val launchMode = LaunchMode.entries[parts[dataStart + 3].toInt()]
                            val searchQuery = parts[dataStart + 4]

                            val season = if (parts.size > dataStart + 5 && parts[dataStart + 5].isNotBlank()) parts[dataStart + 5].toIntOrNull() else null
                            val episode = if (parts.size > dataStart + 6 && parts[dataStart + 6].isNotBlank()) parts[dataStart + 6].toIntOrNull() else null

                            StreamingContent(app, contentId, title, launchMode, searchQuery, season, episode)
                        } else null
                    } else null

                    AlarmItem(id, hour, minute, isActive, streamingContent, volume)
                } else null
            }
        } catch (e: Exception) {
            Log.e("AlarmRepository", "Error loading alarms", e)
            emptyList()
        }
    }
}
