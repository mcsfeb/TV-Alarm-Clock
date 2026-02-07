package com.mcsfeb.tvalarmclock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.mcsfeb.tvalarmclock.data.model.LaunchMode
import com.mcsfeb.tvalarmclock.data.model.StreamingApp
import com.mcsfeb.tvalarmclock.data.model.StreamingContent
import com.mcsfeb.tvalarmclock.ui.components.StreamingAppCard
import com.mcsfeb.tvalarmclock.ui.theme.*

/**
 * ContentPickerScreen - Where the user picks what to wake up to.
 *
 * Simple 2-step flow:
 *
 * STEP 1: Pick a streaming app from the scrollable card grid
 *   - Installed apps are shown first in full color
 *   - Uninstalled apps are dimmed but still selectable
 *
 * STEP 2: Set up what to play (optional content details)
 *   - The app ALWAYS opens at alarm time
 *   - Optionally enter a content ID to go directly to a specific show/episode/channel
 *   - If no content ID is entered, the app just opens to its home screen
 *   - Test launch buttons to verify everything works before setting the alarm
 *
 * The idea is: this is TV automation. Pick an app, tell it what to play,
 * and when the alarm fires it opens that app to that content automatically.
 */
@Composable
fun ContentPickerScreen(
    installedApps: List<StreamingApp>,
    onContentSelected: (StreamingContent) -> Unit,
    onTestLaunch: (StreamingApp, String) -> Unit,
    onTestLaunchAppOnly: (StreamingApp) -> Unit,
    onBack: () -> Unit,
    launchResultMessage: String?
) {
    // Which app is selected (null = still choosing)
    var selectedApp by remember { mutableStateOf<StreamingApp?>(null) }

    // Content details (step 2)
    var contentTitle by remember { mutableStateOf("") }
    var contentId by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
        ) {
            // Header with back button
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        if (selectedApp != null) {
                            // Go back to app selection
                            selectedApp = null
                            contentTitle = ""
                            contentId = ""
                        } else {
                            onBack()
                        }
                    },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(
                        text = if (selectedApp == null) "← Home" else "← Pick Different App",
                        fontSize = 16.sp
                    )
                }
                Spacer(modifier = Modifier.width(24.dp))
                Text(
                    text = if (selectedApp == null)
                        "Pick a Streaming App"
                    else
                        "Set Up ${selectedApp!!.displayName}",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selectedApp != null) Color(selectedApp!!.colorHex) else AlarmBlue
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (selectedApp == null) {
                // ============================================================
                // STEP 1: Pick an app
                // ============================================================
                Text(
                    text = "Which app should the alarm open?",
                    fontSize = 20.sp,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Installed apps are shown first. Select one to set it up.",
                    fontSize = 14.sp,
                    color = TextSecondary.copy(alpha = 0.6f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // App cards - installed first, then the rest
                val sortedApps = remember(installedApps) {
                    val installed = StreamingApp.allSorted().filter { installedApps.contains(it) }
                    val notInstalled = StreamingApp.allSorted().filter { !installedApps.contains(it) }
                    installed + notInstalled
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    items(sortedApps) { app ->
                        val isInstalled = installedApps.contains(app)
                        StreamingAppCard(
                            app = app,
                            isInstalled = isInstalled,
                            isSelected = false,
                            onClick = {
                                selectedApp = app
                                contentTitle = ""
                                contentId = ""
                            }
                        )
                    }
                }

            } else {
                // ============================================================
                // STEP 2: Content details for the selected app
                // ============================================================
                val app = selectedApp!!
                val isInstalled = installedApps.contains(app)

                // App badge + installed status
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color(app.colorHex), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = app.displayName.first().toString(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = app.displayName,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(app.colorHex)
                        )
                        Text(
                            text = if (isInstalled) "Installed on this TV"
                            else "Not installed on this TV",
                            fontSize = 13.sp,
                            color = if (isInstalled) AlarmActiveGreen
                            else AlarmSnoozeOrange
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Content details form
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            2.dp,
                            Color(app.colorHex).copy(alpha = 0.3f),
                            RoundedCornerShape(12.dp)
                        )
                        .background(DarkSurface, RoundedCornerShape(12.dp))
                        .padding(24.dp)
                ) {
                    Column {
                        // Explanation
                        Text(
                            text = "The alarm will open ${app.displayName} automatically.",
                            fontSize = 16.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = "Want it to go to specific content? Fill in the details below. " +
                                    "Or just save now to open the app to its home screen.",
                            fontSize = 14.sp,
                            color = TextSecondary.copy(alpha = 0.7f)
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Name field
                        Text(
                            text = "Name (so you remember what this is):",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        BasicTextField(
                            value = contentTitle,
                            onValueChange = { contentTitle = it },
                            textStyle = TextStyle(fontSize = 18.sp, color = TextPrimary),
                            cursorBrush = SolidColor(AlarmBlue),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    1.dp,
                                    TextSecondary.copy(alpha = 0.3f),
                                    RoundedCornerShape(8.dp)
                                )
                                .background(DarkSurfaceVariant, RoundedCornerShape(8.dp))
                                .padding(14.dp),
                            decorationBox = { innerTextField ->
                                if (contentTitle.isEmpty()) {
                                    Text(
                                        text = getContentNameHint(app),
                                        fontSize = 18.sp,
                                        color = TextSecondary.copy(alpha = 0.4f)
                                    )
                                }
                                innerTextField()
                            }
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Content ID field
                        Text(
                            text = "${app.contentIdLabel} (optional):",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = app.description,
                            fontSize = 13.sp,
                            color = TextSecondary.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = getContentIdTip(app),
                            fontSize = 13.sp,
                            color = AlarmTeal.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        BasicTextField(
                            value = contentId,
                            onValueChange = { contentId = it },
                            textStyle = TextStyle(fontSize = 18.sp, color = TextPrimary),
                            cursorBrush = SolidColor(Color(app.colorHex)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    1.dp,
                                    Color(app.colorHex).copy(alpha = 0.3f),
                                    RoundedCornerShape(8.dp)
                                )
                                .background(DarkSurfaceVariant, RoundedCornerShape(8.dp))
                                .padding(14.dp),
                            decorationBox = { innerTextField ->
                                if (contentId.isEmpty()) {
                                    Text(
                                        text = getContentIdPlaceholder(app),
                                        fontSize = 18.sp,
                                        color = TextSecondary.copy(alpha = 0.4f)
                                    )
                                }
                                innerTextField()
                            }
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Action buttons
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Save as alarm content
                            Button(
                                onClick = {
                                    onContentSelected(
                                        StreamingContent(
                                            app = app,
                                            contentId = contentId.trim(),
                                            title = contentTitle.ifEmpty {
                                                if (contentId.isNotBlank())
                                                    "${app.displayName} Content"
                                                else
                                                    "Open ${app.displayName}"
                                            },
                                            launchMode = if (contentId.isNotBlank())
                                                LaunchMode.DEEP_LINK
                                            else
                                                LaunchMode.APP_ONLY
                                        )
                                    )
                                },
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text(
                                    "Save as Alarm Content",
                                    fontSize = 16.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }

                            // Test deep link (only if content ID is entered)
                            if (contentId.isNotBlank()) {
                                Button(
                                    onClick = { onTestLaunch(app, contentId.trim()) },
                                    colors = ButtonDefaults.colors(
                                        containerColor = Color(app.colorHex)
                                    ),
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Text(
                                        "Test Content Launch",
                                        fontSize = 16.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    )
                                }
                            }

                            // Test open app
                            Button(
                                onClick = { onTestLaunchAppOnly(app) },
                                colors = ButtonDefaults.colors(
                                    containerColor = Color(app.colorHex).copy(alpha = 0.7f)
                                ),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text(
                                    "Test Open App",
                                    fontSize = 16.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Launch result message at the bottom
            if (launchResultMessage != null) {
                Text(
                    text = launchResultMessage,
                    fontSize = 16.sp,
                    color = if (launchResultMessage.startsWith("✓")) AlarmActiveGreen
                    else AlarmFiringRed,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ---- Helper functions for app-specific hints ----

/**
 * Get a placeholder example for the content name field based on the app.
 */
private fun getContentNameHint(app: StreamingApp): String {
    return when (app) {
        StreamingApp.NETFLIX -> "e.g., Stranger Things S2E1"
        StreamingApp.YOUTUBE -> "e.g., Morning Jazz Mix"
        StreamingApp.HULU -> "e.g., The Bear S1E1"
        StreamingApp.DISNEY_PLUS -> "e.g., The Mandalorian S1E1"
        StreamingApp.PRIME_VIDEO -> "e.g., The Boys S1E1"
        StreamingApp.HBO_MAX -> "e.g., House of the Dragon S1E1"
        StreamingApp.SLING_TV -> "e.g., CNN Live"
        StreamingApp.YOUTUBE_TV -> "e.g., ESPN Live"
        StreamingApp.PLUTO_TV -> "e.g., Comedy Central Channel"
        StreamingApp.TUBI -> "e.g., Action Movie Night"
        else -> "e.g., My Favorite Show"
    }
}

/**
 * Get a tip explaining where to find the content ID for each app.
 */
private fun getContentIdTip(app: StreamingApp): String {
    return when (app) {
        StreamingApp.NETFLIX ->
            "Tip: Open the show on netflix.com in a browser. The number in the URL after /title/ is the ID."
        StreamingApp.YOUTUBE ->
            "Tip: The 11-character code after watch?v= in any YouTube link. Example: dQw4w9WgXcQ"
        StreamingApp.HULU ->
            "Tip: Open the episode on hulu.com. The long code after /watch/ is the episode ID."
        StreamingApp.DISNEY_PLUS ->
            "Tip: Open the content on disneyplus.com. The code after /video/ is the content ID."
        StreamingApp.PRIME_VIDEO ->
            "Tip: Open on amazon.com/video. The ASIN code is in the URL after /detail/."
        StreamingApp.HBO_MAX ->
            "Tip: Open on play.max.com. The ID is in the URL after /episode/ or /movie/."
        StreamingApp.SLING_TV ->
            "Tip: Enter a channel ID, or leave blank to just open Sling to your last channel."
        StreamingApp.PLUTO_TV ->
            "Tip: Visit pluto.tv and click a channel. The channel slug in the URL is the ID."
        StreamingApp.YOUTUBE_TV ->
            "Tip: Enter a channel ID or program ID from tv.youtube.com."
        StreamingApp.CRUNCHYROLL ->
            "Tip: Open the episode on crunchyroll.com. The code after /watch/ is the episode ID."
        else ->
            "Tip: Open the content in a browser and look for the ID in the URL."
    }
}

/**
 * Get a placeholder example for the content ID field based on the app.
 */
private fun getContentIdPlaceholder(app: StreamingApp): String {
    return when (app) {
        StreamingApp.NETFLIX -> "e.g., 80057281"
        StreamingApp.YOUTUBE -> "e.g., dQw4w9WgXcQ"
        StreamingApp.HULU -> "e.g., 8c2b6d15-ecdb-4c15-a0d3-6c7d07b0f323"
        StreamingApp.DISNEY_PLUS -> "e.g., content-id-here"
        StreamingApp.PRIME_VIDEO -> "e.g., B0B9HKS79J"
        StreamingApp.HBO_MAX -> "e.g., episode-id-here"
        StreamingApp.SLING_TV -> "e.g., channel-id (or leave blank)"
        StreamingApp.PLUTO_TV -> "e.g., comedy-central"
        StreamingApp.YOUTUBE_TV -> "e.g., channel-or-program-id"
        StreamingApp.CRUNCHYROLL -> "e.g., GRDV0029R"
        else -> "Content ID from the app's URL"
    }
}
