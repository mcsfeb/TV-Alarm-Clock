package com.mcsfeb.tvalarmclock.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.*

/**
 * ContentLaunchService - Foreground service that survives process freezing.
 *
 * When our AlarmActivity launches a streaming app via deep link, the OS freezes
 * our process because we're no longer in the foreground. This service stays alive
 * long enough to:
 * 1. Wait for the streaming app to cold-start (15-30 seconds)
 * 2. Send DPAD_CENTER key events via AdbShell to bypass profile pickers
 * 3. Stop itself when done
 */
class ContentLaunchService : Service() {

    companion object {
        private const val TAG = "ContentLaunchSvc"
        private const val CHANNEL_ID = "content_launch_channel"
        private const val NOTIFICATION_ID = 42

        fun launch(context: Context, packageName: String, deepLinkUri: String, extras: Map<String, String> = emptyMap()) {
            val intent = Intent(context, ContentLaunchService::class.java).apply {
                putExtra("PACKAGE_NAME", packageName)
                putExtra("DEEP_LINK_URI", deepLinkUri)
                for ((k, v) in extras) {
                    putExtra("EXTRA_$k", v)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.i(TAG, "ContentLaunchService started as foreground")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val packageName = intent.getStringExtra("PACKAGE_NAME") ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        val deepLinkUri = intent.getStringExtra("DEEP_LINK_URI") ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        // Collect extras
        val extras = mutableMapOf<String, String>()
        intent.extras?.let { bundle ->
            for (key in bundle.keySet()) {
                if (key.startsWith("EXTRA_")) {
                    extras[key.removePrefix("EXTRA_")] = bundle.getString(key, "")
                }
            }
        }

        serviceScope.launch {
            performLaunchAndBypass(packageName, deepLinkUri, extras)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    /**
     * Complete launch sequence — TESTED on Onn Google TV (Feb 2026).
     *
     * KEY FINDINGS from real device testing:
     *
     * YouTube:     Deep link → auto-plays immediately. No profile picker.
     * Netflix:     Deep link (nflx:// + source=30) → auto-plays. No profile picker on this TV.
     * Paramount+:  Deep link → auto-plays live TV. No profile bypass needed.
     * Hulu:        Deep link → lands on show page. Needs 3x DPAD_CENTER to play.
     * HBO Max:     Deep link → opaque WebView. Profile bypass + extra CENTER needed.
     * Disney+:     Deep link → PIN screen blocks access. Needs PIN entry.
     * Prime Video: Deep link → opens app. Needs valid ASIN to auto-play.
     * Sling:       Deep link BROKEN on cold start (player opens, stream stuck).
     *              FIX: Launch app normally first (resumes last channel), THEN
     *              send deep link to switch channels.
     * Tubi:        Uses tubitv://media-playback/{id} scheme.
     */
    private suspend fun performLaunchAndBypass(packageName: String, deepLinkUri: String, extras: Map<String, String>) {
        Log.i(TAG, "Starting launch sequence for $packageName, uri=$deepLinkUri")

        // Sling needs special handling: deep link is broken on cold start.
        // Launch the app normally first, wait for it to start playing, then
        // send the deep link to switch channels.
        if (packageName == "com.sling") {
            performSlingLaunch(deepLinkUri)
            return
        }

        // Hulu needs special handling: deep link opens show page, not player.
        // Need extra DPAD_CENTER presses to start playback.
        if (packageName == "com.hulu.livingroomplus") {
            performHuluLaunch(deepLinkUri, extras)
            return
        }

        // Standard flow for all other apps
        if (!sendDeepLink(packageName, deepLinkUri, extras)) return

        val waitTime = getAppLoadWaitTime(packageName)
        Log.i(TAG, "Waiting ${waitTime}ms for $packageName to load...")
        delay(waitTime)

        if (!needsProfileBypass(packageName)) {
            Log.i(TAG, "$packageName doesn't need profile bypass — done!")
            return
        }

        // Profile bypass via AdbShell
        AdbShell.init(this@ContentLaunchService)
        sendDpadCenter("Profile bypass CENTER #1")
        delay(3000)
        sendDpadCenter("Profile bypass CENTER #2")
        delay(5000)

        // Re-send deep link for apps whose profile picker absorbs it
        if (needsReDeepLink(packageName)) {
            Log.i(TAG, "Re-sending deep link via AdbShell after profile bypass: $deepLinkUri")
            withContext(Dispatchers.IO) {
                try {
                    val extraArgs = buildString {
                        for ((k, v) in extras) {
                            append(" --es $k '$v'")
                        }
                    }
                    val cmd = "am start -a android.intent.action.VIEW -d '$deepLinkUri' -p $packageName$extraArgs"
                    val sent = AdbShell.sendShellCommand(cmd)
                    Log.i(TAG, "Re-deep-link via AdbShell: success=$sent")
                } catch (e: Exception) {
                    Log.w(TAG, "Re-deep-link via AdbShell failed: ${e.message}")
                }
            }
            delay(15000)

            // Extra CENTER press after re-deep-link to start playback
            // (HBO Max and others may need this to dismiss a dialog or start playing)
            sendDpadCenter("Post re-deep-link CENTER")
        }

        Log.i(TAG, "Content launch sequence complete for $packageName")
    }

    /**
     * Sling TV launch sequence — TESTED Feb 2026.
     *
     * PROBLEM: Sling deep links are BROKEN in ALL scenarios:
     *   - Cold start: Player UI opens but stream gets stuck (ExoPlayer shutter, state=STOPPED)
     *   - Warm (running): Deep link STOPS current playback but never starts new channel
     *
     * SOLUTION: Just launch Sling normally. Sling always auto-plays the last
     * watched channel. Users should set their preferred channel in Sling itself.
     * Channel-specific deep links are unreliable and can break what's already working.
     */
    private suspend fun performSlingLaunch(deepLinkUri: String) {
        Log.i(TAG, "Sling: Launching normally (deep links are broken, will play last channel)")

        // Launch Sling normally (auto-plays last channel)
        val launchIntent = packageManager.getLeanbackLaunchIntentForPackage("com.sling")
            ?: packageManager.getLaunchIntentForPackage("com.sling")
        if (launchIntent == null) {
            Log.e(TAG, "Sling: No launch intent found!")
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)

        // Wait for Sling to fully load and start playing (35s tested on Onn Google TV)
        Log.i(TAG, "Sling: Waiting 35s for app to fully load and start playing...")
        delay(35000)

        // Profile bypass in case profile picker is shown
        AdbShell.init(this@ContentLaunchService)
        sendDpadCenter("Sling profile bypass")
        delay(3000)

        // NOTE: We intentionally do NOT send the channel deep link.
        // TESTED: Sending slingtv://watch.sling.com/watch/live?channelName=...
        // to a running Sling instance STOPS playback but never starts the new channel.
        // Better to let Sling play whatever channel it was on last.
        Log.i(TAG, "Sling: Launch sequence complete (playing last-watched channel)")
    }

    /**
     * Hulu launch sequence — TESTED Feb 2026.
     *
     * PROBLEM: Hulu deep links land on the show page, not the player.
     * The app is completely opaque to accessibility (WebView-based).
     *
     * SOLUTION: Send deep link, wait for load, then send multiple DPAD_CENTER
     * presses: first for profile bypass, then to start playback.
     */
    private suspend fun performHuluLaunch(deepLinkUri: String, extras: Map<String, String>) {
        Log.i(TAG, "Hulu: Force-stopping before launch (required for fresh deep link)")

        // Hulu needs force-stop before re-launch or it ignores the deep link
        withContext(Dispatchers.IO) {
            try {
                AdbShell.init(this@ContentLaunchService)
                AdbShell.sendShellCommand("am force-stop com.hulu.livingroomplus")
            } catch (e: Exception) {
                Log.w(TAG, "Hulu: Force-stop failed: ${e.message}")
            }
        }
        delay(2000)

        // Send the deep link
        if (!sendDeepLink("com.hulu.livingroomplus", deepLinkUri, extras)) return

        // Wait for Hulu to cold-start (25s tested)
        Log.i(TAG, "Hulu: Waiting 25s for cold start...")
        delay(25000)

        // Profile bypass: first CENTER selects profile
        AdbShell.init(this@ContentLaunchService)
        sendDpadCenter("Hulu profile bypass #1")
        delay(3000)

        // Second CENTER: may be needed for secondary dialog or navigation
        sendDpadCenter("Hulu profile bypass #2")
        delay(10000)

        // Third CENTER: starts playback on the show/episode page
        sendDpadCenter("Hulu start playback")
        delay(5000)

        Log.i(TAG, "Hulu: Launch sequence complete")
    }

    /**
     * Helper to send a DPAD_CENTER key event via AdbShell.
     */
    private suspend fun sendDpadCenter(label: String) {
        withContext(Dispatchers.IO) {
            try {
                val sent = AdbShell.sendKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER)
                Log.i(TAG, "$label: success=$sent")
            } catch (e: Exception) {
                Log.w(TAG, "$label failed: ${e.message}")
            }
        }
    }

    /**
     * Send a deep link intent to a streaming app.
     * @return true if the intent was sent successfully
     */
    private fun sendDeepLink(packageName: String, deepLinkUri: String, extras: Map<String, String>): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUri)).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                for ((k, v) in extras) {
                    putExtra(k, v)
                }
            }
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch deep link: ${e.message}")
            false
        }
    }

    /**
     * Apps that show a "Who's Watching?" profile picker on cold start.
     * TESTED on Onn Google TV (Feb 2026).
     *
     * NOTE: Sling and Hulu have their own launch methods above, so they're
     * NOT in this list (their profile bypass is handled in their custom methods).
     *
     * TESTED RESULTS:
     * - Netflix:    No profile picker on this TV (deep link bypasses it)
     * - Paramount+: Auto-plays live TV, no profile picker shown
     * - YouTube:    No profile picker
     * - HBO Max:    Opaque WebView, may show profile picker
     * - Disney+:    Shows PIN screen (needs PIN entry, not just CENTER)
     */
    private fun needsProfileBypass(packageName: String): Boolean = packageName in setOf(
        "com.wbd.stream",                     // HBO Max: opaque but has profile picker
        "com.disney.disneyplus",              // Disney+: profile picker + PIN
        "com.netflix.ninja"                   // Netflix: sometimes shows profile picker
    )

    /**
     * Apps whose profile picker absorbs/loses the original deep link.
     * TESTED on Onn Google TV (Feb 2026):
     * - HBO Max: opaque, likely loses deep link after profile selection
     */
    private fun needsReDeepLink(packageName: String): Boolean = packageName in setOf(
        "com.wbd.stream"
    )

    /**
     * Per-app cold start wait times.
     * TESTED on Onn Google TV (Feb 2026) — these are the actual times
     * measured from intent launch to profile picker / content screen appearance.
     *
     * Sling: 25-30s to profile picker (React Native, slow)
     * HBO Max: 25-30s (WebView-based, very slow)
     * Hulu: 20-25s (WebView-based, slow)
     * Disney+: 20-25s (custom renderer)
     * Paramount+: 15-20s
     * Netflix: 12-15s (Gibbon renderer, faster)
     * Prime Video: 15-20s
     * YouTube: 8-12s (fastest)
     */
    private fun getAppLoadWaitTime(packageName: String): Long = when (packageName) {
        "com.sling" -> 30000               // Sling: profile picker at ~25s, wait 30s
        "com.wbd.stream" -> 30000          // HBO Max: very slow WebView cold start
        "com.hulu.livingroomplus" -> 25000  // Hulu: WebView-based, slow
        "com.disney.disneyplus" -> 25000    // Disney+: custom renderer, slow
        "com.cbs.ott" -> 20000             // Paramount+: moderately slow
        "com.amazon.amazonvideo.livingroom" -> 20000  // Prime Video: moderate
        "com.netflix.ninja" -> 15000       // Netflix: Gibbon renderer, faster
        "com.google.android.youtube.tv" -> 12000      // YouTube: relatively fast
        else -> 20000
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Content Launch",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Launching streaming content"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Launching content...")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Launching content...")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.i(TAG, "ContentLaunchService destroyed")
    }
}
