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
import com.mcsfeb.tvalarmclock.data.model.AlarmItem
import com.mcsfeb.tvalarmclock.data.model.StreamingContent
import com.mcsfeb.tvalarmclock.ui.components.*
import com.mcsfeb.tvalarmclock.ui.theme.*

/**
 * HomeScreen - The main screen of the TV Alarm Clock app.
 *
 * Shows:
 * - A live clock (current time)
 * - List of alarms (with add/delete/toggle/edit)
 * - Time picker for adding new alarms
 * - "Test Alarm" button that fires immediately
 * - Selected streaming content info (for the next alarm to be created)
 * - Button to pick streaming app
 */
@Composable
fun HomeScreen(
    alarms: List<AlarmItem>,
    onAddAlarm: (hour: Int, minute: Int, content: StreamingContent?) -> Unit,
    onDeleteAlarm: (AlarmItem) -> Unit,
    onToggleAlarm: (AlarmItem) -> Unit,
    onTestAlarm: () -> Unit,
    onPickStreamingApp: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    isAccessibilityEnabled: Boolean,
    contentForNewAlarm: StreamingContent?,
    onContentForNewAlarmSelected: (StreamingContent) -> Unit,
    onEditAlarmContent: ((AlarmItem) -> Unit)? = null
) {
    var showTimePicker by remember { mutableStateOf(false) }

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
                Text(
                    text = "TV Alarm Clock",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = AlarmBlue,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                ClockDisplay()

                Spacer(modifier = Modifier.height(24.dp))

                // Streaming content card for the NEXT alarm
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .border(
                            width = 2.dp,
                            color = if (contentForNewAlarm != null) AlarmTeal else DarkSurfaceVariant,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .background(DarkSurface, RoundedCornerShape(16.dp))
                        .padding(20.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (contentForNewAlarm != null) "Next Alarm Will Open" else "No App Selected",
                            fontSize = 16.sp,
                            color = if (contentForNewAlarm != null) AlarmTeal else TextSecondary
                        )
                        if (contentForNewAlarm != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "${contentForNewAlarm.app.displayName}: ${contentForNewAlarm.title}",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(contentForNewAlarm.app.colorHex),
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TVButton(
                        text = "Pick Streaming App",
                        color = AlarmTeal,
                        onClick = onPickStreamingApp
                    )
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
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
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

                if (showTimePicker) {
                    TimePicker(
                        onTimeSet = { hour24, minute ->
                            onAddAlarm(hour24, minute, contentForNewAlarm)
                            showTimePicker = false
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

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
                                onDelete = { onDeleteAlarm(alarm) },
                                onEditContent = onEditAlarmContent?.let { callback ->
                                    { callback(alarm) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
