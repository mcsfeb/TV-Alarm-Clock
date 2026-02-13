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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcsfeb.tvalarmclock.data.model.StreamingContent
import com.mcsfeb.tvalarmclock.ui.components.TVButton
import com.mcsfeb.tvalarmclock.ui.components.TimePicker
import com.mcsfeb.tvalarmclock.ui.theme.*

/**
 * AlarmSetupScreen - Set a time, then pick what content to play.
 *
 * Two-step flow:
 * 1. Pick the alarm time (hour + minute + AM/PM)
 * 2. Pick the streaming content (opens ContentPickerScreen)
 *
 * When editing an existing alarm, the time is pre-filled and the user
 * can change just the content or the time.
 */
@Composable
fun AlarmSetupScreen(
    editingHour: Int? = null,
    editingMinute: Int? = null,
    selectedContent: StreamingContent?,
    onPickContent: () -> Unit,
    onSave: (hour: Int, minute: Int, content: StreamingContent?) -> Unit,
    onBack: () -> Unit
) {
    // If editing, pre-fill the time; otherwise start with defaults
    var hour by remember { mutableIntStateOf(editingHour ?: 7) }
    var minute by remember { mutableIntStateOf(editingMinute ?: 0) }
    var timeSet by remember { mutableStateOf(editingHour != null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(40.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                TVButton(
                    text = "\u2190 Back",
                    color = TextSecondary,
                    compact = true,
                    onClick = onBack
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = if (editingHour != null) "Edit Alarm" else "New Alarm",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                // ===== LEFT: Time Picker =====
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "1. Set Alarm Time",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (timeSet) AlarmActiveGreen else AlarmBlue
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (timeSet) {
                        // Show the chosen time with ability to change
                        val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
                        val amPm = if (hour < 12) "AM" else "PM"
                        Text(
                            text = "%d:%02d %s".format(displayHour, minute, amPm),
                            fontSize = 64.sp,
                            fontWeight = FontWeight.Bold,
                            color = AlarmActiveGreen
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        TVButton(
                            text = "Change Time",
                            color = TextSecondary,
                            compact = true,
                            onClick = { timeSet = false }
                        )
                    } else {
                        TimePicker(
                            onTimeSet = { h, m ->
                                hour = h
                                minute = m
                                timeSet = true
                            }
                        )
                    }
                }

                // ===== RIGHT: Content Picker =====
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "2. Choose What to Play",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selectedContent != null) AlarmActiveGreen else AlarmTeal
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (selectedContent != null) {
                        // Show selected content
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .border(
                                    2.dp,
                                    Color(selectedContent.app.colorHex),
                                    RoundedCornerShape(16.dp)
                                )
                                .background(DarkSurface, RoundedCornerShape(16.dp))
                                .padding(20.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = selectedContent.app.displayName,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(selectedContent.app.colorHex)
                                )
                                if (selectedContent.title.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = selectedContent.title,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = selectedContent.launchMode.name.replace("_", " "),
                                    fontSize = 13.sp,
                                    color = TextSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        TVButton(
                            text = "Change Content",
                            color = AlarmTeal,
                            compact = true,
                            onClick = onPickContent
                        )
                    } else {
                        // No content selected yet
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .border(
                                    2.dp,
                                    DarkSurfaceVariant,
                                    RoundedCornerShape(16.dp)
                                )
                                .background(DarkSurface, RoundedCornerShape(16.dp))
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No content selected.\nThe alarm will just wake the TV.",
                                fontSize = 15.sp,
                                color = TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        TVButton(
                            text = "Pick Streaming App",
                            color = AlarmTeal,
                            onClick = onPickContent
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Save button at the bottom
            TVButton(
                text = if (editingHour != null) "Save Changes" else "Create Alarm",
                color = if (timeSet) AlarmActiveGreen else TextSecondary,
                enabled = timeSet,
                onClick = {
                    if (timeSet) {
                        onSave(hour, minute, selectedContent)
                    }
                }
            )
        }
    }
}
