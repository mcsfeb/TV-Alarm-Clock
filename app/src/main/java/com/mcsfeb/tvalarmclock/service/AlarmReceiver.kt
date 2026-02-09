package com.mcsfeb.tvalarmclock.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mcsfeb.tvalarmclock.ui.screens.AlarmActivity

/**
 * AlarmReceiver - Fires when the scheduled alarm time arrives.
 *
 * This is the heart of the alarm system. When the alarm goes off:
 * 1. Android calls onReceive() even if the app is closed
 * 2. WakeUpHelper grabs a WakeLock to turn the TV screen on
 * 3. We launch AlarmActivity which shows the alarm countdown
 * 4. The Android TV OS automatically sends HDMI-CEC to turn on the physical TV
 * 5. After the countdown, AlarmActivity reads saved content and launches the streaming app
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Step 1: Wake up the TV screen
        WakeUpHelper.acquireWakeLock(context)

        // Step 2: Read saved content info and pass it to AlarmActivity
        val prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        val contentApp = prefs.getString("content_app", null)
        val contentId = prefs.getString("content_id", "") ?: ""
        val contentTitle = prefs.getString("content_title", "") ?: ""
        val contentMode = prefs.getString("content_mode", "APP_ONLY") ?: "APP_ONLY"
        val contentSearchQuery = prefs.getString("content_search_query", "") ?: ""

        // Step 3: Launch the alarm screen with content info
        val alarmId = intent.getIntExtra("ALARM_ID", 0)

        val alarmIntent = Intent(context, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("ALARM_ID", alarmId)
            putExtra("CONTENT_APP", contentApp)
            putExtra("CONTENT_ID", contentId)
            putExtra("CONTENT_TITLE", contentTitle)
            putExtra("CONTENT_MODE", contentMode)
            putExtra("CONTENT_SEARCH_QUERY", contentSearchQuery)
        }
        context.startActivity(alarmIntent)
    }
}
