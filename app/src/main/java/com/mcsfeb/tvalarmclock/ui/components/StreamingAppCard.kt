package com.mcsfeb.tvalarmclock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import com.mcsfeb.tvalarmclock.data.model.StreamingApp

/**
 * StreamingAppCard - A clickable card representing a streaming service.
 *
 * Shows the app name with its brand color. When the user navigates to it
 * with the D-pad and presses Select, it triggers onSelect.
 *
 * On a real TV, the user uses the remote's arrow keys to move between cards
 * and the center button to select one.
 */
@Composable
fun StreamingAppCard(
    app: StreamingApp,
    isInstalled: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val brandColor = Color(app.colorHex)
    val borderColor = if (isSelected) Color.White else Color.Transparent

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(180.dp)
            .height(120.dp),
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(12.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = brandColor.copy(alpha = 0.85f),
            focusedContainerColor = brandColor,
            pressedContainerColor = brandColor.copy(alpha = 0.7f)
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, Color.White),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1.1f
        )
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().padding(12.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = app.displayName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!isInstalled) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Not Installed",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
