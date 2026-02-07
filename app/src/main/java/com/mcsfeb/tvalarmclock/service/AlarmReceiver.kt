package com.mcsfeb.tvalarmclock.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.mcsfeb.tvalarmclock.ui.screens.AlarmActivity

/**
 * AlarmReceiver - Fires when the scheduled alarm time arrives.
 *
 * This is the heart of the alarm system. When the alarm goes off:
 * 1. Android calls onReceive() even if the app is closed
 * 2. We grab a WakeLock to turn the TV screen on
 * 3. We launch AlarmActivity which shows the alarm UI
 * 4. The Android TV OS automatically sends HDMI-CEC to turn on the physical TV
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Step 1: Wake up the TV screen
        // FULL_WAKE_LOCK is deprecated but is the ONLY way to turn on the screen
        // from a BroadcastReceiver (before an Activity exists)
        // ACQUIRE_CAUSES_WAKEUP = actually turn the screen on, not just keep it on
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        @Suppress("DEPRECATION")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK
                    or PowerManager.ACQUIRE_CAUSES_WAKEUP
                    or PowerManager.ON_AFTER_RELEASE,
            "TVAlarmClock::AlarmWakeLock"
        )
        // Hold the wake lock for up to 5 minutes (safety net - released when Activity starts)
        wakeLock.acquire(5 * 60 * 1000L)

        // Step 2: Launch the alarm screen
        val alarmId = intent.getIntExtra("ALARM_ID", 0)

        val alarmIntent = Intent(context, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("ALARM_ID", alarmId)
        }
        context.startActivity(alarmIntent)
    }
}
