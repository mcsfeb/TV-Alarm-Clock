package com.mcsfeb.tvalarmclock.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mcsfeb.tvalarmclock.data.repository.AlarmRepository
import java.util.*

/**
 * BootReceiver - Re-registers alarms after the TV reboots.
 *
 * Android clears ALL scheduled alarms when the device restarts.
 * This receiver fires after boot, loads saved alarms from SharedPreferences,
 * and re-schedules each active alarm with AlarmManager.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val alarmRepo = AlarmRepository(context)
        val scheduler = AlarmScheduler(context)
        val alarms = alarmRepo.loadAlarms()

        for (alarm in alarms) {
            if (!alarm.isActive) continue

            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, alarm.hour)
                set(Calendar.MINUTE, alarm.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                // If the time has already passed today, schedule for tomorrow
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            scheduler.schedule(cal.timeInMillis, alarmId = alarm.id)
        }
    }
}
