package com.mcsfeb.tvalarmclock.data.model

import java.text.SimpleDateFormat
import java.util.*

/**
 * AlarmItem - Represents a single alarm in the list.
 */
data class AlarmItem(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val isActive: Boolean,
    val streamingContent: StreamingContent? = null // Now holds StreamingContent
) {
    /** Formatted time string like "7:30 AM" */
    fun formattedTime(): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
        return formatter.format(cal.time)
    }

    /** User-friendly label for the alarm (e.g., "Netflix: Stranger Things") */
    fun getLabel(): String {
        return streamingContent?.let {
            "${it.app.displayName}: ${it.title}"
        } ?: "No Content Selected"
    }
}
