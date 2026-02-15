package com.mcsfeb.tvalarmclock.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcsfeb.tvalarmclock.data.model.AlarmItem
import com.mcsfeb.tvalarmclock.player.ProfileAutoSelector
import com.mcsfeb.tvalarmclock.ui.components.*
import com.mcsfeb.tvalarmclock.ui.theme.*

/**
 * HomeScreen - Clean main screen with just the clock and alarm list.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    alarms: List<AlarmItem>,
    onAddAlarm: () -> Unit,
    onEditAlarm: (AlarmItem) -> Unit,
    onDeleteAlarm: (AlarmItem) -> Unit,
    onToggleAlarm: (AlarmItem) -> Unit,
    onTestAlarm: (AlarmItem) -> Unit
) {
    val context = LocalContext.current
    val isA11yEnabled = ProfileAutoSelector.isServiceEnabled()

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

                TVButton(
                    text = "+ Add Alarm",
                    color = AlarmBlue,
                    onClick = onAddAlarm
                )
                
                if (!isA11yEnabled) {
                    Spacer(modifier = Modifier.height(16.dp))
                    TVButton(
                        text = "Enable Smart Assistant",
                        color = AlarmSnoozeOrange,
                        compact = true,
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    )
                    Text(
                        "Required for reliable app launching",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
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
                        modifier = Modifier
                            .weight(1f)
                            .focusRestorer()
                    ) {
                        items(alarms) { alarm ->
                            AlarmCard(
                                alarm = alarm,
                                onToggle = { onToggleAlarm(alarm) },
                                onDelete = { onDeleteAlarm(alarm) },
                                onEditContent = { onEditAlarm(alarm) },
                                onTest = { onTestAlarm(alarm) }
                            )
                        }
                    }
                }
            }
        }
    }
}
