package com.mcsfeb.tvalarmclock.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mcsfeb.tvalarmclock.data.config.DeepLinkConfig
import com.mcsfeb.tvalarmclock.data.repository.AlarmRepository
import com.mcsfeb.tvalarmclock.player.ContentLauncher
import com.mcsfeb.tvalarmclock.ui.screens.AlarmActivity

/**
 * AlarmReceiver - Fires when the scheduled alarm time arrives.
 * 
 * Updated to use ContentLauncher for smart content launching.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Step 0: Load deep link config
        DeepLinkConfig.load(context)

        // Step 1: Wake up the TV screen
        WakeUpHelper.acquireWakeLock(context)

        // Step 2: Find the specific alarm that fired
        val alarmId = intent.getIntExtra("ALARM_ID", 0)
        val alarmRepo = AlarmRepository(context)
        val alarm = alarmRepo.loadAlarms().find { it.id == alarmId }
        val content = alarm?.streamingContent

        // Step 3: Check Smart Launch setting
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val smartLaunchEnabled = prefs.getBoolean("smart_launch_enabled", true)

        if (smartLaunchEnabled && content != null) {
            // SMART LAUNCH: Launch content directly using hybrid system
            val identifiers = mutableMapOf<String, String>()
            identifiers["id"] = content.contentId
            identifiers["title"] = content.title
            
            // Map specific fields
            identifiers["showName"] = content.searchQuery.ifBlank { content.title }
            identifiers["channelName"] = content.title // For Sling/Live TV
            
            content.seasonNumber?.let { identifiers["season"] = it.toString() }
            content.episodeNumber?.let { identifiers["episode"] = it.toString() }

            // Determine content type (simple heuristic)
            val contentType = if (content.app.name == "SLING_TV" || content.launchMode.name == "APP_ONLY") {
                "live"
            } else {
                "episode"
            }

            ContentLauncher.getInstance(context).launchContent(
                packageName = content.app.packageName,
                contentType = contentType,
                identifiers = identifiers
            )
            
            // Note: We skip launching AlarmActivity here to avoid double-launch/interruption.
            // The user wakes up directly to the content.
        } else {
            // LEGACY / FALLBACK: Launch AlarmActivity (full screen alarm UI)
            val alarmIntent = Intent(context, AlarmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("ALARM_ID", alarmId)
                
                content?.let {
                    putExtra("CONTENT_APP", it.app.name)
                    putExtra("CONTENT_ID", it.contentId)
                    putExtra("CONTENT_TITLE", it.title)
                    putExtra("CONTENT_MODE", it.launchMode.name)
                    putExtra("CONTENT_SEARCH_QUERY", it.searchQuery)
                    if (it.seasonNumber != null) putExtra("CONTENT_SEASON", it.seasonNumber)
                    if (it.episodeNumber != null) putExtra("CONTENT_EPISODE", it.episodeNumber)
                }
            }
            context.startActivity(alarmIntent)
        }
    }
}
