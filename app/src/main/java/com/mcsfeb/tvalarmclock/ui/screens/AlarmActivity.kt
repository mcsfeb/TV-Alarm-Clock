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
import androidx.compose.material3.MaterialTheme
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
import com.mcsfeb.tvalarmclock.ui.theme.AlarmFiringRed
import com.mcsfeb.tvalarmclock.ui.theme.TVAlarmClockTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

/**
 * AlarmActivity - The full-screen alarm display.
 *
 * This is what you see when the alarm fires:
 * - Big flashing "ALARM!" text
 * - Current time display
 * - Instructions to dismiss (press any button on the remote)
 *
 * The screen turns on automatically because:
 * 1. AlarmReceiver already acquired a WakeLock
 * 2. This Activity has turnScreenOn=true in the manifest
 * 3. We also set FLAG_KEEP_SCREEN_ON so the TV stays on
 */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the screen on while the alarm is showing
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // For older Android versions, also use these flags
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

        setContent {
            TVAlarmClockTheme {
                AlarmFiringScreen(
                    onDismiss = { finish() }
                )
            }
        }
    }
}

/**
 * AlarmFiringScreen - The visual alarm display with flashing text.
 *
 * Shows a big red flashing "ALARM!" with the current time.
 * Press any key on the TV remote to dismiss.
 */
@Composable
fun AlarmFiringScreen(onDismiss: () -> Unit) {
    // Flashing animation - toggles between visible and dim every 500ms
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

    // Current time, updates every second
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
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
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
                fontSize = 120.sp,
                fontWeight = FontWeight.Bold,
                color = AlarmFiringRed.copy(alpha = alpha),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Current time
            Text(
                text = currentTime,
                fontSize = 64.sp,
                fontWeight = FontWeight.Light,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Dismiss instruction
            Text(
                text = "Press any button to dismiss",
                fontSize = 24.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Returns the current time formatted as "7:30 AM" */
private fun getCurrentTime(): String {
    val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    return formatter.format(Date())
}
