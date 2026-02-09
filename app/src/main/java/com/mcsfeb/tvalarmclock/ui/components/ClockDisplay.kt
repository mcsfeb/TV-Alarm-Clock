package com.mcsfeb.tvalarmclock.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcsfeb.tvalarmclock.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

/**
 * ClockDisplay - Live clock that updates every second.
 * Shows time like "7:30:45 AM" with the AM/PM on the right.
 */
@Composable
fun ClockDisplay() {
    var currentTimeOnly by remember { mutableStateOf(getTimeOnly()) }
    var currentAmPm by remember { mutableStateOf(getAmPm()) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTimeOnly = getTimeOnly()
            currentAmPm = getAmPm()
            delay(1000)
        }
    }

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
