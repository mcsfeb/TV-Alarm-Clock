package com.mcsfeb.tvalarmclock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcsfeb.tvalarmclock.data.model.AlarmItem
import com.mcsfeb.tvalarmclock.ui.components.*
import com.mcsfeb.tvalarmclock.ui.theme.*

/**
 * HomeScreen - Clean main screen with just the clock and alarm list.
 *
 * Shows:
 * - A live clock (current time)
 * - List of existing alarms
 * - "+ Add Alarm" button (goes to AlarmSetupScreen)
 * - "Test Alarm" button (fires alarm instantly)
 *
 * Content picking happens INSIDE the alarm setup flow, not here.
 */
@Composable
fun HomeScreen(
    alarms: List<AlarmItem>,
    onAddAlarm: () -> Unit,
    onEditAlarm: (AlarmItem) -> Unit,
    onDeleteAlarm: (AlarmItem) -> Unit,
    onToggleAlarm: (AlarmItem) -> Unit,
    onTestAlarm: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(40.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp)
        ) {
            // ===== LEFT SIDE: Clock =====
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

                Spacer(modifier = Modifier.height(40.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TVButton(
                        text = "+ Add Alarm",
                        color = AlarmBlue,
                        onClick = onAddAlarm
                    )
                    TVButton(
                        text = "Test Alarm",
                        color = AlarmSnoozeOrange,
                        onClick = onTestAlarm
                    )
                }
            }

            // ===== RIGHT SIDE: Alarm List =====
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Text(
                    text = "Alarms",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (alarms.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No alarms set.\nPress \"+ Add Alarm\" to create one.",
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
                                onEditContent = { onEditAlarm(alarm) }
                            )
                        }
                    }
                }
            }
        }
    }
}
