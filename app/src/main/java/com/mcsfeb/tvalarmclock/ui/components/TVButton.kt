package com.mcsfeb.tvalarmclock.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.mcsfeb.tvalarmclock.ui.theme.*

/**
 * TVButton - A proper Android TV button with clear focus/pressed states.
 *
 * On Android TV, users navigate with a D-pad (arrow keys + center select).
 * Buttons need to clearly show:
 * - Default: the normal resting state
 * - Focused: when the D-pad highlight is on this button (bright border + glow)
 * - Pressed: when the user clicks center/enter (slightly darker)
 */
@Composable
fun TVButton(
    text: String,
    color: Color,
    onClick: () -> Unit,
    compact: Boolean = false,
    enabled: Boolean = true
) {
    Button(
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
            border = Border(
                border = BorderStroke(1.5.dp, color.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(10.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.5.dp, Color.White),
                shape = RoundedCornerShape(10.dp)
            ),
            pressedBorder = Border(
                border = BorderStroke(2.dp, color),
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
