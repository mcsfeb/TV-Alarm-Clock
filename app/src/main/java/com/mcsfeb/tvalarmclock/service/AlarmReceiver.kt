package com.mcsfeb.tvalarmclock.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mcsfeb.tvalarmclock.data.config.DeepLinkConfig
import com.mcsfeb.tvalarmclock.data.repository.AlarmRepository
import com.mcsfeb.tvalarmclock.ui.screens.AlarmActivity

/**
 * AlarmReceiver - Fires when the scheduled alarm time arrives.
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

        // Step 3: Launch the alarm screen with content info
        val alarmIntent = Intent(context, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("ALARM_ID", alarmId)
            
            alarm?.streamingContent?.let { content ->
                putExtra("CONTENT_APP", content.app.name)
                putExtra("CONTENT_ID", content.contentId)
                putExtra("CONTENT_TITLE", content.title)
                putExtra("CONTENT_MODE", content.launchMode.name)
                putExtra("CONTENT_SEARCH_QUERY", content.searchQuery)
            }
        }
        context.startActivity(alarmIntent)
    }
}
