package com.mcsfeb.tvalarmclock.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mcsfeb.tvalarmclock.data.config.DeepLinkConfig
import com.mcsfeb.tvalarmclock.data.repository.AlarmRepository
import com.mcsfeb.tvalarmclock.ui.screens.AlarmActivity

/**
 * AlarmReceiver - Fires when the scheduled alarm time arrives.
 *
 * DESIGN: Always routes through AlarmActivity.
 *
 * WHY NOT call ContentLaunchService directly here?
 *
 * Android 10+ BAL_BLOCK: A background foreground service (ContentLaunchService)
 * cannot call startActivity() unless the app has a visible window on screen.
 * Without a visible window, Android blocks the streaming app launch with error:
 *   "Background activity launch blocked [inVisibleTask: false]"
 *
 * AlarmManager.setAlarmClock() grants AlarmReceiver a short-lived (~5s) BAL allowlist,
 * so we CAN start AlarmActivity from here. AlarmActivity then stays visible for 10s,
 * which is long enough for ContentLaunchService's coroutine to call startActivity()
 * on the streaming app (startActivity() happens at T+3..T+7s after the service starts).
 *
 * Result: streaming app launches reliably every time.
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Step 1: Load deep link config (needed by AlarmActivity for SEARCH mode)
        DeepLinkConfig.load(context)

        // Step 2: Wake up the TV screen
        WakeUpHelper.acquireWakeLock(context)

        // Step 3: Find the specific alarm that fired
        val alarmId = intent.getIntExtra("ALARM_ID", 0)
        val alarmRepo = AlarmRepository(context)
        val alarm = alarmRepo.loadAlarms().find { it.id == alarmId }
        val content = alarm?.streamingContent
        val volume = alarm?.volume ?: -1

        Log.i(TAG, "Alarm $alarmId fired: content='${content?.title ?: "none"}' mode=${content?.launchMode?.name ?: "N/A"}")

        // Step 4: Launch AlarmActivity — it handles SEARCH/DEEP_LINK/APP_ONLY routing
        // and stays visible long enough for ContentLaunchService to open the streaming app.
        val alarmIntent = Intent(context, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("ALARM_ID", alarmId)
            putExtra("VOLUME", volume)

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
