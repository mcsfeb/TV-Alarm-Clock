package com.mcsfeb.tvalarmclock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcsfeb.tvalarmclock.ui.screens.AlarmItem
import com.mcsfeb.tvalarmclock.ui.theme.*

/**
 * AlarmCard - Shows a single alarm in the list with toggle and delete.
 */
@Composable
fun AlarmCard(
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

            TVButton(
                text = if (alarm.isActive) "ON" else "OFF",
                color = if (alarm.isActive) AlarmActiveGreen else TextSecondary,
                compact = true,
                onClick = onToggle
            )

            Spacer(modifier = Modifier.width(8.dp))

            TVButton(
                text = "\u2715",
                color = AlarmFiringRed,
                compact = true,
                onClick = onDelete
            )
        }
    }
}
