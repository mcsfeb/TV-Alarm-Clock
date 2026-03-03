package com.mcsfeb.tvalarmclock.ui.screens

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcsfeb.tvalarmclock.data.config.DeepLinkConfig
import com.mcsfeb.tvalarmclock.data.model.LaunchMode
import com.mcsfeb.tvalarmclock.data.model.StreamingApp
import com.mcsfeb.tvalarmclock.data.model.StreamingContent
import com.mcsfeb.tvalarmclock.player.ContentLauncher
import com.mcsfeb.tvalarmclock.service.AlarmAccessibilityService
import com.mcsfeb.tvalarmclock.service.AlarmScheduler
import com.mcsfeb.tvalarmclock.service.ContentLaunchService
import com.mcsfeb.tvalarmclock.service.WakeUpHelper
import com.mcsfeb.tvalarmclock.ui.components.TVButton
import com.mcsfeb.tvalarmclock.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * AlarmActivity - Full-screen alarm display when the alarm fires.
 */
class AlarmActivity : ComponentActivity() {

    private lateinit var alarmScheduler: AlarmScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        alarmScheduler = AlarmScheduler(this)

        // Ensure screen turns on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            )
        } else {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        // Extract Alarm Data
        val alarmId = intent.getIntExtra("ALARM_ID", 0)
        val contentAppName = intent.getStringExtra("CONTENT_APP")
        val contentId = intent.getStringExtra("CONTENT_ID") ?: ""
        val contentTitle = intent.getStringExtra("CONTENT_TITLE") ?: ""
        val contentModeName = intent.getStringExtra("CONTENT_MODE") ?: "APP_ONLY"
        val searchQuery = intent.getStringExtra("CONTENT_SEARCH_QUERY") ?: ""
        
        val volume = intent.getIntExtra("VOLUME", -1)
        val season = if (intent.hasExtra("CONTENT_SEASON")) intent.getIntExtra("CONTENT_SEASON", -1) else null
        val episode = if (intent.hasExtra("CONTENT_EPISODE")) intent.getIntExtra("CONTENT_EPISODE", -1) else null

        val streamingApp = contentAppName?.let {
            try { StreamingApp.valueOf(it) } catch (_: Exception) { null }
        }
        val launchMode = try { LaunchMode.valueOf(contentModeName) } catch (_: Exception) { LaunchMode.APP_ONLY }

        val content = streamingApp?.let {
            StreamingContent(it, contentId, contentTitle, launchMode, searchQuery, season, episode)
        }

        // ---- LAUNCH CONTENT ----
        if (content != null) {
            // Start ContentLaunchService with the right URL/mode for this content.
            //
            // CRITICAL — Do NOT finish() immediately!
            //
            // Android 10+ BAL_BLOCK: ContentLaunchService (background foreground service) calls
            // startActivity() to open the streaming app at ~T+3 to T+7 seconds after it starts.
            // Android blocks that call with "inVisibleTask: false" unless our app has a visible
            // Activity on screen at that moment.
            //
            // AlarmActivity IS that visible Activity. By staying visible for 10 seconds we give
            // ContentLaunchService's coroutine enough time to call startActivity() successfully.
            // After 10s the streaming app is already open and we auto-dismiss.

            // Build the service launch URL based on content mode.
            DeepLinkConfig.load(this)
            val launchUrl: String = when {
                content.launchMode == LaunchMode.SEARCH && content.searchQuery.isNotBlank() -> {
                    val template = DeepLinkConfig.getSearchUrl(content.app.name)
                    if (template != null) {
                        val encoded = content.searchQuery.trim().replace(" ", "+")
                        template.replace("{query}", encoded).replace("{phrase}", encoded)
                    } else {
                        "APP_ONLY"  // App has no search URL (e.g., Sling)
                    }
                }
                else -> "APP_ONLY"  // APP_ONLY / DEEP_LINK handled below via ContentLauncher
            }

            if (launchUrl != "APP_ONLY") {
                // SEARCH mode: route directly to ContentLaunchService with search URL.
                // Pass season/episode so the service can navigate to the specific episode.
                val launchExtras = buildMap<String, String> {
                    content.seasonNumber?.let { s -> if (s > 0) put("season", s.toString()) }
                    content.episodeNumber?.let { e -> if (e > 0) put("episode", e.toString()) }
                }
                ContentLaunchService.launch(this, content.app.packageName, launchUrl, launchExtras, volume)
            } else {
                // DEEP_LINK / APP_ONLY: ContentLauncher builds the correct deep link URI
                val identifiers = mutableMapOf<String, String>()
                identifiers["id"] = content.contentId
                identifiers["title"] = content.title
                identifiers["showName"] = content.searchQuery.ifBlank { content.title }
                identifiers["channelName"] = content.title
                content.seasonNumber?.let { s -> identifiers["season"] = s.toString() }
                content.episodeNumber?.let { e -> identifiers["episode"] = e.toString() }
                val type = if (content.app.name == "SLING_TV" || content.launchMode == LaunchMode.APP_ONLY) "live" else "episode"
                ContentLauncher.getInstance(this).launchContent(content.app.packageName, type, identifiers, volume)
            }

            // Auto-dismiss after 10 seconds (streaming app should be open by then).
            // User can also dismiss or snooze early via the buttons.
            lifecycleScope.launch {
                delay(10000)
                WakeUpHelper.releaseWakeLock()
                finish()
            }

            // Show alarm UI while ContentLaunchService warms up the streaming app.
            setContent {
                TVAlarmClockTheme {
                    AlarmFiringScreen(
                        streamingContent = content,
                        onDismiss = {
                            WakeUpHelper.releaseWakeLock()
                            finish()
                        },
                        onLaunchContent = { /* ContentLaunchService handles this */ },
                        onSnooze = {
                            val snoozeTime = System.currentTimeMillis() + 5 * 60 * 1000L
                            alarmScheduler.schedule(snoozeTime, alarmId = alarmId)
                            WakeUpHelper.releaseWakeLock()
                            finish()
                        }
                    )
                }
            }
            return
        }

        // ---- NO CONTENT: show the alarm UI with manual dismiss/snooze ----
        if (!AlarmAccessibilityService.isRunning()) {
            Toast.makeText(this, "Enable 'Smart Assistant' in settings for reliable launching!", Toast.LENGTH_LONG).show()
        }

        setContent {
            TVAlarmClockTheme {
                AlarmFiringScreen(
                    streamingContent = null,
                    onDismiss = {
                        WakeUpHelper.releaseWakeLock()
                        finish()
                    },
                    onLaunchContent = { /* No-op */ },
                    onSnooze = {
                        val snoozeTime = System.currentTimeMillis() + 5 * 60 * 1000L
                        alarmScheduler.schedule(snoozeTime, alarmId = alarmId)
                        WakeUpHelper.releaseWakeLock()
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
fun AlarmFiringScreen(
    streamingContent: StreamingContent?,
    onDismiss: () -> Unit,
    onLaunchContent: () -> Unit,
    onSnooze: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "flash")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "flashAlpha"
    )

    var currentTime by remember { mutableStateOf(getCurrentTime()) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = getCurrentTime()
            delay(1000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "ALARM!",
                fontSize = 100.sp,
                fontWeight = FontWeight.Bold,
                color = AlarmFiringRed.copy(alpha = alpha),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = currentTime,
                fontSize = 56.sp,
                fontWeight = FontWeight.Light,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Only "Snooze" and "Dismiss" buttons needed now
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TVButton(
                    text = "Snooze 5 min",
                    color = AlarmSnoozeOrange,
                    onClick = onSnooze
                )

                TVButton(
                    text = "Dismiss",
                    color = TextSecondary,
                    onClick = onDismiss
                )
            }
        }
    }
}

private fun getCurrentTime(): String {
    val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    return formatter.format(Date())
}
