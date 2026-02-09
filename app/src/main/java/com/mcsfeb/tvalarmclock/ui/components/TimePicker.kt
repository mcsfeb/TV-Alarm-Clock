package com.mcsfeb.tvalarmclock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcsfeb.tvalarmclock.ui.theme.*

/**
 * TimePicker - Hour/Minute/AM-PM selector for setting alarm times.
 *
 * Uses up/down arrows to adjust values:
 * - Hour: 1-12
 * - Minute: 0-55 in increments of 5
 * - AM/PM toggle button
 *
 * Calls onTimeSet with the 24-hour format when "Set Alarm" is pressed.
 */
@Composable
fun TimePicker(
    onTimeSet: (hour24: Int, minute: Int) -> Unit
) {
    var pickerHour by remember { mutableIntStateOf(7) }
    var pickerMinute by remember { mutableIntStateOf(0) }
    var pickerAmPm by remember { mutableStateOf("AM") }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, AlarmBlue, RoundedCornerShape(16.dp))
            .background(DarkSurface, RoundedCornerShape(16.dp))
            .padding(20.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Set Alarm Time",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = AlarmBlue
            )
            Spacer(modifier = Modifier.height(16.dp))

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

                // AM/PM toggle
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
                    onTimeSet(hour24, pickerMinute)
                }
            )
        }
    }
}
