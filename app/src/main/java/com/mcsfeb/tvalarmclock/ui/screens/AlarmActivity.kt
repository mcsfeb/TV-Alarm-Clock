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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcsfeb.tvalarmclock.data.model.LaunchMode
import com.mcsfeb.tvalarmclock.data.model.StreamingApp
import com.mcsfeb.tvalarmclock.player.ProfileAutoSelector
import com.mcsfeb.tvalarmclock.player.StreamingLauncher
import com.mcsfeb.tvalarmclock.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

/**
 * AlarmActivity - The full-screen alarm display.
 *
 * When the alarm fires:
 * 1. Shows "ALARM!" with the current time and what content will play
 * 2. Counts down from 10 seconds
 * 3. Auto-launches the selected streaming app/content
 * 4. Press any button to dismiss WITHOUT launching
 *
 * If no streaming content was selected, it just shows the alarm screen.
 */
class AlarmActivity : ComponentActivity() {

    private lateinit var streamingLauncher: StreamingLauncher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable auto-profile-select for alarm launches
        // This auto-clicks past "Who's Watching?" profile screens
        // so the show starts playing even when the user is asleep
        streamingLauncher = StreamingLauncher(this, autoProfileSelect = true)

        // Keep the screen on
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

        // Read content info from the intent
        val contentAppName = intent.getStringExtra("CONTENT_APP")
        val contentId = intent.getStringExtra("CONTENT_ID") ?: ""
        val contentTitle = intent.getStringExtra("CONTENT_TITLE") ?: ""
        val contentMode = intent.getStringExtra("CONTENT_MODE") ?: "APP_ONLY"
        val searchQuery = intent.getStringExtra("CONTENT_SEARCH_QUERY") ?: ""

        val streamingApp = contentAppName?.let {
            try { StreamingApp.valueOf(it) } catch (e: Exception) { null }
        }

        val launchMode = try {
            LaunchMode.valueOf(contentMode)
        } catch (e: Exception) { LaunchMode.APP_ONLY }

        setContent {
            TVAlarmClockTheme {
                AlarmFiringScreen(
                    streamingApp = streamingApp,
                    contentTitle = contentTitle,
                    onDismiss = {
                        // User pressed a button to dismiss - cancel any pending
                        // auto-profile-select key presses since they're awake
                        ProfileAutoSelector.cancelPending()
                        finish()
                    },
                    onLaunchContent = {
                        if (streamingApp != null) {
                            when (launchMode) {
                                LaunchMode.DEEP_LINK -> {
                                    if (contentId.isNotEmpty()) {
                                        streamingLauncher.launch(streamingApp, contentId)
                                    } else {
                                        streamingLauncher.launchAppOnly(streamingApp)
                                    }
                                }
                                LaunchMode.SEARCH -> {
                                    if (searchQuery.isNotEmpty()) {
                                        streamingLauncher.launchWithSearch(streamingApp, searchQuery)
                                    } else {
                                        streamingLauncher.launchAppOnly(streamingApp)
                                    }
                                }
                                LaunchMode.APP_ONLY -> {
                                    streamingLauncher.launchAppOnly(streamingApp)
                                }
                            }
                            finish()
                        }
                    }
                )
            }
        }
    }
}

/**
 * AlarmFiringScreen - The visual alarm display with countdown to content launch.
 */
@Composable
fun AlarmFiringScreen(
    streamingApp: StreamingApp?,
    contentTitle: String,
    onDismiss: () -> Unit,
    onLaunchContent: () -> Unit
) {
    // Flashing animation
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

    // Current time
    var currentTime by remember { mutableStateOf(getCurrentTime()) }

    // Countdown timer (10 seconds if content is set, otherwise no countdown)
    var countdown by remember { mutableIntStateOf(if (streamingApp != null) 10 else -1) }
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
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    dismissed = true
                    onDismiss()
                    true
                } else false
            }
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Big flashing ALARM text
            Text(
                text = "ALARM!",
                fontSize = 100.sp,
                fontWeight = FontWeight.Bold,
                color = AlarmFiringRed.copy(alpha = alpha),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Current time
            Text(
                text = currentTime,
                fontSize = 56.sp,
                fontWeight = FontWeight.Light,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // What's about to launch
            if (streamingApp != null && !dismissed) {
                Text(
                    text = "Opening in $countdown seconds...",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(streamingApp.colorHex),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "${streamingApp.displayName}: $contentTitle",
                    fontSize = 22.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Press any button to cancel and dismiss",
                    fontSize = 18.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    text = "Press any button to dismiss",
                    fontSize = 24.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private fun getCurrentTime(): String {
    val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    return formatter.format(Date())
}
