package com.mcsfeb.tvalarmclock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcsfeb.tvalarmclock.data.model.AlarmItem
import com.mcsfeb.tvalarmclock.ui.theme.*

/**
 * AlarmCard - Shows a single alarm in the list.
 *
 * Each alarm displays:
 * - The time (e.g., "7:30 AM")
 * - What content it will launch (e.g., "Netflix: Stranger Things")
 * - Toggle ON/OFF button
 * - Edit button to change the alarm's assigned content
 * - Delete button
 */
@Composable
fun AlarmCard(
    alarm: AlarmItem,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onEditContent: (() -> Unit)? = null
) {
    val contentColor = if (alarm.isActive) {
        alarm.streamingContent?.let { Color(it.app.colorHex) } ?: AlarmTeal
    } else {
        TextSecondary.copy(alpha = 0.6f)
    }

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

                Text(
                    text = alarm.getLabel(),
                    fontSize = 14.sp,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Edit content button - lets user change what this alarm opens
            if (onEditContent != null) {
                TVButton(
                    text = "\u270E",  // Pencil icon
                    color = AlarmTeal,
                    compact = true,
                    onClick = onEditContent
                )
                Spacer(modifier = Modifier.width(8.dp))
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
