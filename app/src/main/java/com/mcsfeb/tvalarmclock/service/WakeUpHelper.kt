package com.mcsfeb.tvalarmclock.service

import android.content.Context
import android.os.PowerManager

/**
 * WakeUpHelper - Centralizes all wake-up logic for turning the TV on.
 *
 * When the alarm fires, we need to:
 * 1. Acquire a WakeLock to turn the screen on (FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP)
 * 2. Android TV OS automatically sends HDMI-CEC "One Touch Play" which turns on the physical TV
 * 3. The WakeLock is held for a limited time, then released automatically
 *
 * This class is used by AlarmReceiver (when the alarm fires) and could be used
 * by any other component that needs to wake the TV screen.
 */
object WakeUpHelper {

    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Acquire a WakeLock to turn on the TV screen.
     *
     * @param context The context
     * @param timeoutMs How long to hold the WakeLock (default: 5 minutes).
     *   After this time, the lock auto-releases even if we forget to release it.
     */
    @Suppress("DEPRECATION")
    fun acquireWakeLock(context: Context, timeoutMs: Long = 5 * 60 * 1000L) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK
                    or PowerManager.ACQUIRE_CAUSES_WAKEUP
                    or PowerManager.ON_AFTER_RELEASE,
            "TVAlarmClock::AlarmWakeLock"
        )
        wakeLock?.acquire(timeoutMs)
    }

    /**
     * Release the WakeLock if it's still held.
     * Call this when the alarm is dismissed or snoozed.
     */
    fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }
}
