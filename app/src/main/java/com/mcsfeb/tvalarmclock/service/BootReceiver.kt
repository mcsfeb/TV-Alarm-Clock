package com.mcsfeb.tvalarmclock.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * BootReceiver - Re-registers alarms after the TV reboots.
 *
 * Android clears ALL scheduled alarms when the device restarts.
 * This receiver fires after boot and re-schedules any saved alarms.
 *
 * For Milestone 1: This is a placeholder. Once we have a database of alarms
 * (Milestone 3), this will load them and re-schedule each one.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // TODO (Milestone 3): Load saved alarms from Room database
            // and re-schedule each one with AlarmScheduler
            //
            // val scheduler = AlarmScheduler(context)
            // val alarms = database.getAllActiveAlarms()
            // alarms.forEach { alarm ->
            //     scheduler.schedule(alarm.triggerTimeMillis, alarm.id)
            // }
        }
    }
}
