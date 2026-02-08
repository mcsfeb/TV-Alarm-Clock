package com.mcsfeb.tvalarmclock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ButtonDefaults
import com.mcsfeb.tvalarmclock.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

/**
 * AlarmItem - Represents a single alarm in the list.
 *
 * Each alarm has:
 * - A unique ID (used for AlarmManager scheduling)
 * - An hour and minute (24-hour format internally)
 * - Whether it's currently active
 * - What content it will launch (shown as text)
 */
data class AlarmItem(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val isActive: Boolean,
    val label: String = ""  // e.g., "Netflix: Friends S1E1"
) {
    /** Formatted time string like "7:30 AM" */
    fun formattedTime(): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
        return formatter.format(cal.time)
    }
}

/**
 * HomeScreen - The main screen of the TV Alarm Clock app.
 *
 * Shows:
 * - A live clock (current time)
 * - List of alarms (with add/delete/toggle)
 * - Time picker for adding new alarms
 * - "Test Alarm" button that fires immediately
 * - Selected streaming content info
 * - Button to pick streaming app
 */
@Composable
fun HomeScreen(
    alarms: List<AlarmItem>,
    onAddAlarm: (hour: Int, minute: Int) -> Unit,
    onDeleteAlarm: (AlarmItem) -> Unit,
    onToggleAlarm: (AlarmItem) -> Unit,
    onTestAlarm: () -> Unit,
    onPickStreamingApp: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    isAccessibilityEnabled: Boolean,
    selectedAppName: String?
) {
    // Live clock that updates every second
    var currentTimeOnly by remember { mutableStateOf(getTimeOnly()) }
    var currentAmPm by remember { mutableStateOf(getAmPm()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTimeOnly = getTimeOnly()
            currentAmPm = getAmPm()
            delay(1000)
        }
    }

    // Time picker state
    var showTimePicker by remember { mutableStateOf(false) }
    var pickerHour by remember { mutableIntStateOf(7) }
    var pickerMinute by remember { mutableIntStateOf(0) }
    var pickerAmPm by remember { mutableStateOf("AM") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(40.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            // ===== LEFT SIDE: Clock + Streaming Info =====
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center
            ) {
                // App title
                Text(
                    text = "TV Alarm Clock",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = AlarmBlue,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Current time (big clock) with AM/PM on the right
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = currentTimeOnly,
                        fontSize = 80.sp,
                        fontWeight = FontWeight.Light,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = currentAmPm,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Medium,
                        color = AlarmTeal,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Streaming content card
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .border(
                            width = 2.dp,
                            color = if (selectedAppName != null) AlarmTeal else DarkSurfaceVariant,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .background(DarkSurface, RoundedCornerShape(16.dp))
                        .padding(20.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (selectedAppName != null) "Wake Up To" else "No App Selected",
                            fontSize = 16.sp,
                            color = if (selectedAppName != null) AlarmTeal else TextSecondary
                        )
                        if (selectedAppName != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = selectedAppName,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Bottom buttons
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Pick streaming app button
                    TVButton(
                        text = "Pick Streaming App",
                        color = AlarmTeal,
                        onClick = onPickStreamingApp
                    )

                    // Test alarm button
                    TVButton(
                        text = "Test Alarm Now",
                        color = AlarmSnoozeOrange,
                        onClick = onTestAlarm
                    )
                }

                // Accessibility service warning
                if (!isAccessibilityEnabled) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .border(
                                1.dp,
                                AlarmSnoozeOrange.copy(alpha = 0.5f),
                                RoundedCornerShape(12.dp)
                            )
                            .background(
                                AlarmSnoozeOrange.copy(alpha = 0.1f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Enable auto-profile-click to skip\n\"Who's Watching?\" screens",
                                fontSize = 13.sp,
                                color = AlarmSnoozeOrange,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TVButton(
                                text = "Open Accessibility Settings",
                                color = AlarmSnoozeOrange,
                                compact = true,
                                onClick = onOpenAccessibilitySettings
                            )
                        }
                    }
                }
            }

            // ===== RIGHT SIDE: Alarm List =====
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                // Header with Add button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Alarms",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    TVButton(
                        text = if (showTimePicker) "Cancel" else "+ Add Alarm",
                        color = if (showTimePicker) AlarmFiringRed else AlarmBlue,
                        onClick = { showTimePicker = !showTimePicker }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Time picker (shown when adding a new alarm)
                if (showTimePicker) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(2.dp, AlarmBlue, RoundedCornerShape(16.dp))
                            .background(DarkSurface, RoundedCornerShape(16.dp))
                            .padding(20.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Set Alarm Time",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = AlarmBlue
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            // Hour / Minute / AM-PM selectors
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.Bottom,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // Hour
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    TVButton(
                                        text = "\u25B2",
                                        color = AlarmBlue,
                                        compact = true,
                                        onClick = { pickerHour = if (pickerHour >= 12) 1 else pickerHour + 1 }
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = pickerHour.toString(),
                                        fontSize = 48.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    TVButton(
                                        text = "\u25BC",
                                        color = AlarmBlue,
                                        compact = true,
                                        onClick = { pickerHour = if (pickerHour <= 1) 12 else pickerHour - 1 }
                                    )
                                }

                                Text(
                                    ":",
                                    fontSize = 48.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )

                                // Minute
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    TVButton(
                                        text = "\u25B2",
                                        color = AlarmBlue,
                                        compact = true,
                                        onClick = { pickerMinute = (pickerMinute + 5) % 60 }
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = String.format("%02d", pickerMinute),
                                        fontSize = 48.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    TVButton(
                                        text = "\u25BC",
                                        color = AlarmBlue,
                                        compact = true,
                                        onClick = { pickerMinute = if (pickerMinute <= 0) 55 else pickerMinute - 5 }
                                    )
                                }

                                Spacer(modifier = Modifier.width(24.dp))

                                // AM/PM - sits to the right, aligned to the bottom
                                // so it lines up next to the numbers, not in the middle
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                ) {
                                    TVButton(
                                        text = pickerAmPm,
                                        color = if (pickerAmPm == "AM") AlarmBlue else AlarmTeal,
                                        onClick = { pickerAmPm = if (pickerAmPm == "AM") "PM" else "AM" }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            TVButton(
                                text = "Set Alarm",
                                color = AlarmActiveGreen,
                                onClick = {
                                    // Convert 12-hour to 24-hour
                                    val hour24 = when {
                                        pickerAmPm == "AM" && pickerHour == 12 -> 0
                                        pickerAmPm == "PM" && pickerHour == 12 -> 12
                                        pickerAmPm == "PM" -> pickerHour + 12
                                        else -> pickerHour
                                    }
                                    onAddAlarm(hour24, pickerMinute)
                                    showTimePicker = false
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Alarm list
                if (alarms.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No alarms set.\nTap \"+ Add Alarm\" to create one.",
                            fontSize = 18.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(alarms) { alarm ->
                            AlarmCard(
                                alarm = alarm,
                                onToggle = { onToggleAlarm(alarm) },
                                onDelete = { onDeleteAlarm(alarm) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * AlarmCard - Shows a single alarm in the list with toggle and delete.
 */
@Composable
private fun AlarmCard(
    alarm: AlarmItem,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 2.dp,
                color = if (alarm.isActive) AlarmActiveGreen.copy(alpha = 0.5f) else DarkSurfaceVariant,
                shape = RoundedCornerShape(12.dp)
            )
            .background(DarkSurface, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Time
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = alarm.formattedTime(),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (alarm.isActive) TextPrimary else TextSecondary
                )
                if (alarm.label.isNotBlank()) {
                    Text(
                        text = alarm.label,
                        fontSize = 14.sp,
                        color = if (alarm.isActive) AlarmTeal else TextSecondary.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Toggle button
            TVButton(
                text = if (alarm.isActive) "ON" else "OFF",
                color = if (alarm.isActive) AlarmActiveGreen else TextSecondary,
                compact = true,
                onClick = onToggle
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Delete button
            TVButton(
                text = "\u2715",
                color = AlarmFiringRed,
                compact = true,
                onClick = onDelete
            )
        }
    }
}

/**
 * TVButton - A proper Android TV button with clear focus/pressed states.
 *
 * On Android TV, users navigate with a D-pad (arrow keys + center select).
 * Buttons need to clearly show:
 * - Default: the normal resting state
 * - Focused: when the D-pad highlight is on this button (bright border + glow)
 * - Pressed: when the user clicks center/enter (slightly darker)
 *
 * This uses the TV Material3 Button with customized colors and borders
 * so every button on every screen looks clean and obvious when focused.
 */
@Composable
fun TVButton(
    text: String,
    color: Color,
    onClick: () -> Unit,
    compact: Boolean = false,
    enabled: Boolean = true
) {
    androidx.tv.material3.Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.colors(
            containerColor = color.copy(alpha = 0.2f),
            contentColor = color,
            focusedContainerColor = color,
            focusedContentColor = Color.White,
            pressedContainerColor = color.copy(alpha = 0.7f),
            pressedContentColor = Color.White,
            disabledContainerColor = DarkSurfaceVariant,
            disabledContentColor = TextSecondary.copy(alpha = 0.4f)
        ),
        border = ButtonDefaults.border(
            border = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(
                    1.5.dp, color.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(10.dp)
            ),
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(
                    2.5.dp, Color.White
                ),
                shape = RoundedCornerShape(10.dp)
            ),
            pressedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(
                    2.dp, color
                ),
                shape = RoundedCornerShape(10.dp)
            )
        ),
        shape = ButtonDefaults.shape(
            shape = RoundedCornerShape(10.dp)
        ),
        scale = ButtonDefaults.scale(
            focusedScale = 1.08f
        ),
        modifier = if (compact) Modifier.height(44.dp) else Modifier.height(52.dp)
    ) {
        Text(
            text = text,
            fontSize = if (compact) 15.sp else 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = if (compact) 6.dp else 12.dp)
        )
    }
}

/** Returns just the time part like "7:30:45" */
private fun getTimeOnly(): String {
    val formatter = SimpleDateFormat("h:mm:ss", Locale.getDefault())
    return formatter.format(Date())
}

/** Returns just "AM" or "PM" */
private fun getAmPm(): String {
    val formatter = SimpleDateFormat("a", Locale.getDefault())
    return formatter.format(Date())
}
