package com.mcsfeb.tvalarmclock.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.mcsfeb.tvalarmclock.ui.screens.AlarmActivity

/**
 * AlarmScheduler - Wraps Android's AlarmManager to schedule and cancel alarms.
 *
 * Uses setAlarmClock() which is the most reliable method for alarm clock apps:
 * - Never gets batched or delayed by the system
 * - Works even in Doze mode
 * - Shows an alarm icon in the system status bar
 * - USE_EXACT_ALARM permission is auto-granted for alarm apps
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Schedule an alarm to fire at the given time.
     *
     * @param triggerTimeMillis When the alarm should fire (in milliseconds since epoch)
     * @param alarmId A unique ID for this alarm (used to cancel it later)
     */
    fun schedule(triggerTimeMillis: Long, alarmId: Int = 0) {
        // This intent fires when the alarm goes off
        val fireIntent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("ALARM_ID", alarmId)
        }
        val firePendingIntent = PendingIntent.getBroadcast(
            context,
            alarmId,
            fireIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // This intent opens when the user taps the alarm icon in the status bar
        val showIntent = Intent(context, AlarmActivity::class.java)
        val showPendingIntent = PendingIntent.getActivity(
            context,
            alarmId,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Schedule it!
        val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTimeMillis, showPendingIntent)
        alarmManager.setAlarmClock(alarmClockInfo, firePendingIntent)
    }

    /**
     * Cancel a previously scheduled alarm.
     *
     * @param alarmId The ID of the alarm to cancel
     */
    fun cancel(alarmId: Int = 0) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarmId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}
