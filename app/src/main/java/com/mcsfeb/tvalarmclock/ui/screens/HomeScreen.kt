package com.mcsfeb.tvalarmclock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.mcsfeb.tvalarmclock.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

/**
 * HomeScreen - The main screen of the TV Alarm Clock app.
 *
 * For Milestone 1, this shows:
 * - A live clock (current time)
 * - A hardcoded alarm time
 * - A "Set Alarm" button that schedules the alarm for 30 seconds from now
 * - Status text showing whether the alarm is set
 *
 * In future milestones, this will show a list of all alarms and content choices.
 */
@Composable
fun HomeScreen(
    onSetAlarm: () -> Unit,
    onCancelAlarm: () -> Unit,
    isAlarmSet: Boolean,
    alarmTimeText: String
) {
    // Live clock that updates every second
    var currentTime by remember { mutableStateOf(getCurrentTimeFormatted()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = getCurrentTimeFormatted()
            delay(1000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(48.dp)
        ) {
            // App title
            Text(
                text = "TV Alarm Clock",
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = AlarmBlue,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Current time (big clock)
            Text(
                text = currentTime,
                fontSize = 96.sp,
                fontWeight = FontWeight.Light,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Alarm info card
            Box(
                modifier = Modifier
                    .border(
                        width = 2.dp,
                        color = if (isAlarmSet) AlarmActiveGreen else DarkSurfaceVariant,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .background(
                        color = DarkSurface,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(32.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isAlarmSet) "Alarm Set For" else "No Alarm Set",
                        fontSize = 24.sp,
                        color = if (isAlarmSet) AlarmActiveGreen else TextSecondary
                    )

                    if (isAlarmSet) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = alarmTimeText,
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Buttons row
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Set alarm button (schedules alarm 30 seconds from now for testing)
                Button(
                    onClick = onSetAlarm,
                    modifier = Modifier.height(56.dp)
                ) {
                    Text(
                        text = if (isAlarmSet) "Reset Alarm (30 sec)" else "Set Alarm (30 sec)",
                        fontSize = 20.sp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                // Cancel button (only visible when alarm is set)
                if (isAlarmSet) {
                    Button(
                        onClick = onCancelAlarm,
                        colors = ButtonDefaults.colors(
                            containerColor = AlarmFiringRed
                        ),
                        modifier = Modifier.height(56.dp)
                    ) {
                        Text(
                            text = "Cancel Alarm",
                            fontSize = 20.sp,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Helper text
            Text(
                text = "Milestone 1: Press the button to set an alarm 30 seconds from now",
                fontSize = 16.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Returns the current time formatted as "7:30:45 AM" */
private fun getCurrentTimeFormatted(): String {
    val formatter = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
    return formatter.format(Date())
}
