package com.mcsfeb.tvalarmclock.data.repository

import android.content.Context
import com.mcsfeb.tvalarmclock.data.model.LaunchMode
import com.mcsfeb.tvalarmclock.data.model.StreamingApp
import com.mcsfeb.tvalarmclock.data.model.StreamingContent

/**
 * ContentRepository - Saves and loads selected streaming content from SharedPreferences.
 *
 * This data is read by both MainActivity (to show what's selected) and
 * AlarmReceiver (to know what app to launch when the alarm fires).
 */
class ContentRepository(context: Context) {

    private val prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)

    fun saveContent(content: StreamingContent) {
        prefs.edit()
            .putString("content_app", content.app.name)
            .putString("content_id", content.contentId)
            .putString("content_title", content.title)
            .putString("content_mode", content.launchMode.name)
            .putString("content_search_query", content.searchQuery)
            .apply()
    }

    fun loadContent(): StreamingContent? {
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
}
