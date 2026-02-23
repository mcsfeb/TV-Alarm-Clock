package com.mcsfeb.tvalarmclock.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * TestLaunchReceiver - Exported broadcast receiver for live ADB testing.
 *
 * Since ContentLaunchService is not exported (security), this thin wrapper
 * receives broadcasts from ADB and calls ContentLaunchService.launch() internally.
 *
 * USAGE from ADB:
 *   adb shell am broadcast -a com.mcsfeb.tvalarmclock.TEST_LAUNCH \
 *     --es pkg com.sling \
 *     --es uri APP_ONLY \
 *     --es channelName ESPN \
 *     --ei vol 50
 *
 * Extras:
 *   pkg         (String) - package name, e.g. com.sling, com.disney.disneyplus, com.wbd.stream
 *   uri         (String) - deep link URI or "APP_ONLY"
 *   channelName (String) - Sling channel name, e.g. "ESPN", "Fox News", "CNN"
 *   searchQuery (String) - Show name for HBO/Disney+, e.g. "Succession", "The Bear"
 *   vol         (int)    - Volume 0-100, omit or -1 to leave unchanged
 */
class TestLaunchReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TestLaunchReceiver"
        const val ACTION = "com.mcsfeb.tvalarmclock.TEST_LAUNCH"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val pkg         = intent.getStringExtra("pkg") ?: return
        val uri         = intent.getStringExtra("uri") ?: "APP_ONLY"
        val channelName = intent.getStringExtra("channelName") ?: ""
        val searchQuery = intent.getStringExtra("searchQuery") ?: ""
        val vol         = intent.getIntExtra("vol", -1)

        Log.i(TAG, "TEST_LAUNCH received: pkg=$pkg uri=$uri channel='$channelName' search='$searchQuery' vol=$vol")

        val extras = mutableMapOf<String, String>()
        if (channelName.isNotBlank()) extras["channelName"] = channelName
        if (searchQuery.isNotBlank()) extras["searchQuery"] = searchQuery

        ContentLaunchService.launch(
            context  = context,
            packageName = pkg,
            deepLinkUri = uri,
            extras   = extras,
            volume   = vol
        )

        Log.i(TAG, "ContentLaunchService started for $pkg")
    }
}
