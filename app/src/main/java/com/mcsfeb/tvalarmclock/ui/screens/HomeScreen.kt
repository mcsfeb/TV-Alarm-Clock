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
import androidx.compose.ui.graphics.Color
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
 * Shows:
 * - A live clock (current time)
 * - Alarm status and time
 * - Selected streaming app (if any)
 * - Buttons: Set Alarm, Pick Streaming App, Cancel Alarm
 */
@Composable
fun HomeScreen(
    onSetAlarm: () -> Unit,
    onCancelAlarm: () -> Unit,
    onPickStreamingApp: () -> Unit,
    isAlarmSet: Boolean,
    alarmTimeText: String,
    selectedAppName: String?
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

            Spacer(modifier = Modifier.height(32.dp))

            // Status cards row
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.Top
            ) {
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
                        .padding(24.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isAlarmSet) "Alarm Set For" else "No Alarm Set",
                            fontSize = 20.sp,
                            color = if (isAlarmSet) AlarmActiveGreen else TextSecondary
                        )

                        if (isAlarmSet) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = alarmTimeText,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                    }
                }

                // Streaming app card
                Box(
                    modifier = Modifier
                        .border(
                            width = 2.dp,
                            color = if (selectedAppName != null) AlarmTeal else DarkSurfaceVariant,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .background(
                            color = DarkSurface,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(24.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (selectedAppName != null) "Wake Up To" else "No App Selected",
                            fontSize = 20.sp,
                            color = if (selectedAppName != null) AlarmTeal else TextSecondary
                        )

                        if (selectedAppName != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = selectedAppName,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            // Buttons row
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Set alarm button
                Button(
                    onClick = onSetAlarm,
                    modifier = Modifier.height(56.dp)
                ) {
                    Text(
                        text = if (isAlarmSet) "Reset Alarm (30 sec)" else "Set Alarm (30 sec)",
                        fontSize = 18.sp,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }

                // Pick streaming app button
                Button(
                    onClick = onPickStreamingApp,
                    colors = ButtonDefaults.colors(
                        containerColor = AlarmTeal
                    ),
                    modifier = Modifier.height(56.dp)
                ) {
                    Text(
                        text = "Pick Streaming App",
                        fontSize = 18.sp,
                        modifier = Modifier.padding(horizontal = 12.dp)
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
                            fontSize = 18.sp,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Returns the current time formatted as "7:30:45 AM" */
private fun getCurrentTimeFormatted(): String {
    val formatter = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
    return formatter.format(Date())
}
