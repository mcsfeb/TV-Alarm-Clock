package com.mcsfeb.tvalarmclock.ui.screens

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
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
import com.mcsfeb.tvalarmclock.data.model.LaunchMode
import com.mcsfeb.tvalarmclock.data.model.StreamingApp
import com.mcsfeb.tvalarmclock.data.model.StreamingContent
import com.mcsfeb.tvalarmclock.player.ProfileAutoSelector
import com.mcsfeb.tvalarmclock.player.StreamingLauncher
import com.mcsfeb.tvalarmclock.service.AlarmScheduler
import com.mcsfeb.tvalarmclock.service.WakeUpHelper
import com.mcsfeb.tvalarmclock.ui.components.TVButton
import com.mcsfeb.tvalarmclock.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

/**
 * AlarmActivity - Full-screen alarm display when the alarm fires.
 *
 * Shows:
 * - Flashing "ALARM!" text
 * - Current time
 * - What content will be launched (app + show name)
 * - Auto-countdown to launch (10 seconds)
 * - "Launch Now" button to skip the countdown
 * - "Snooze 5 min" button to delay and go back to sleep
 * - "Dismiss" button to cancel entirely
 */
class AlarmActivity : ComponentActivity() {

    private lateinit var streamingLauncher: StreamingLauncher
    private lateinit var alarmScheduler: AlarmScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        streamingLauncher = StreamingLauncher(this, autoProfileSelect = true)
        alarmScheduler = AlarmScheduler(this)

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

        val alarmId = intent.getIntExtra("ALARM_ID", 0)
        val contentAppName = intent.getStringExtra("CONTENT_APP")
        val contentId = intent.getStringExtra("CONTENT_ID") ?: ""
        val contentTitle = intent.getStringExtra("CONTENT_TITLE") ?: ""
        val contentModeName = intent.getStringExtra("CONTENT_MODE") ?: "APP_ONLY"
        val searchQuery = intent.getStringExtra("CONTENT_SEARCH_QUERY") ?: ""

        val streamingApp = contentAppName?.let {
            try { StreamingApp.valueOf(it) } catch (_: Exception) { null }
        }
        val launchMode = try { LaunchMode.valueOf(contentModeName) } catch (_: Exception) { LaunchMode.APP_ONLY }

        val content = streamingApp?.let {
            StreamingContent(it, contentId, contentTitle, launchMode, searchQuery)
        }

        setContent {
            TVAlarmClockTheme {
                AlarmFiringScreen(
                    streamingContent = content,
                    onDismiss = {
                        ProfileAutoSelector.cancelPending()
                        WakeUpHelper.releaseWakeLock()
                        finish()
                    },
                    onLaunchContent = {
                        content?.let {
                            streamingLauncher.launch(it)
                        }
                        finish()
                    },
                    onSnooze = {
                        // Schedule a new alarm 5 minutes from now
                        val snoozeTime = System.currentTimeMillis() + 5 * 60 * 1000L
                        alarmScheduler.schedule(snoozeTime, alarmId = alarmId)
                        ProfileAutoSelector.cancelPending()
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

    var countdown by remember { mutableIntStateOf(if (streamingContent != null) 10 else -1) }
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = getCurrentTime()
            if (countdown > 0 && !dismissed) {
                countdown--
            } else if (countdown == 0 && !dismissed) {
                onLaunchContent()
                countdown = -1
            }
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

            if (streamingContent != null && !dismissed) {
                Text(
                    text = "Opening in $countdown seconds...",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(streamingContent.app.colorHex),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "${streamingContent.app.displayName}: ${streamingContent.title}",
                    fontSize = 22.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Action buttons row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TVButton(
                        text = "Launch Now",
                        color = Color(streamingContent.app.colorHex),
                        onClick = {
                            dismissed = true
                            onLaunchContent()
                        }
                    )

                    TVButton(
                        text = "Snooze 5 min",
                        color = AlarmSnoozeOrange,
                        onClick = {
                            dismissed = true
                            onSnooze()
                        }
                    )

                    TVButton(
                        text = "Dismiss",
                        color = TextSecondary,
                        onClick = {
                            dismissed = true
                            onDismiss()
                        }
                    )
                }
            } else {
                // No content assigned or dismissed - just show dismiss/snooze
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TVButton(
                        text = "Snooze 5 min",
                        color = AlarmSnoozeOrange,
                        onClick = {
                            dismissed = true
                            onSnooze()
                        }
                    )

                    TVButton(
                        text = "Dismiss",
                        color = TextSecondary,
                        onClick = {
                            dismissed = true
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

private fun getCurrentTime(): String {
    val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    return formatter.format(Date())
}
