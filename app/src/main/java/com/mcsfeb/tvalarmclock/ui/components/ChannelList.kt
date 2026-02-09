package com.mcsfeb.tvalarmclock.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import com.mcsfeb.tvalarmclock.data.model.LiveChannel
import com.mcsfeb.tvalarmclock.data.model.StreamingApp
import com.mcsfeb.tvalarmclock.ui.theme.*

/**
 * ChannelList - Scrollable list of live TV channels for a streaming app.
 */
@Composable
fun ChannelList(
    channels: List<LiveChannel>,
    app: StreamingApp,
    onChannelPicked: (LiveChannel) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(channels) { channel ->
            Surface(
                onClick = { onChannelPicked(channel) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = DarkSurface,
                    focusedContainerColor = Color(app.colorHex).copy(alpha = 0.2f)
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, Color(app.colorHex)),
                        shape = RoundedCornerShape(8.dp)
                    )
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        channel.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    Text(
                        channel.category.displayName,
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}
