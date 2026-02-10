package com.mcsfeb.tvalarmclock.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import com.mcsfeb.tvalarmclock.data.model.ContentInfo
import com.mcsfeb.tvalarmclock.data.model.MediaType
import com.mcsfeb.tvalarmclock.data.model.StreamingApp
import com.mcsfeb.tvalarmclock.data.remote.ContentIdMapper
import com.mcsfeb.tvalarmclock.ui.theme.*

/**
 * SearchResultCard - Shows a single search result (TV show or movie) from TMDB.
 *
 * @param availableOn List of streaming apps that have this content (from TMDB watch providers).
 *                    If non-null, shows colored dots for each available app.
 *                    Green checkmark if the selected app has it.
 */
@Composable
fun SearchResultCard(
    content: ContentInfo,
    app: StreamingApp,
    availableOn: List<StreamingApp>? = null,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(80.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = DarkSurface,
            focusedContainerColor = Color(app.colorHex).copy(alpha = 0.15f)
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, Color(app.colorHex)),
                shape = RoundedCornerShape(10.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Type badge
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(app.colorHex).copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (content.mediaType == MediaType.TV_SHOW) "TV" else "Film",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    content.title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${content.year} \u2022 ${if (content.mediaType == MediaType.TV_SHOW) "TV Show" else "Movie"}",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                    // Show which streaming apps have this content
                    if (availableOn != null && availableOn.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        val isOnSelectedApp = availableOn.contains(app)
                        if (isOnSelectedApp) {
                            Text(
                                "\u2713 On ${app.displayName}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = AlarmActiveGreen
                            )
                        } else {
                            Text(
                                "On: ${availableOn.take(3).joinToString { it.displayName }}",
                                fontSize = 12.sp,
                                color = AlarmTeal.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            // Show launch mode indicator
            val hasMapping = ContentIdMapper.getContentId(content.tmdbId, app) != null
            Text(
                if (hasMapping) "Direct Link" else "Search",
                fontSize = 12.sp,
                color = if (hasMapping) AlarmActiveGreen else AlarmTeal
            )
        }
    }
}
