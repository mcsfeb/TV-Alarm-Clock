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
import com.mcsfeb.tvalarmclock.player.UrlParser
import com.mcsfeb.tvalarmclock.ui.components.StreamingAppCard
import com.mcsfeb.tvalarmclock.ui.theme.*

/**
 * ContentPickerScreen - Where the user picks what to wake up to.
 *
 * Two ways to select content:
 *
 * 1. PASTE A URL: The user pastes a URL from their browser
 *    (e.g., "https://www.netflix.com/title/80057281")
 *    and we automatically detect the app and extract the content ID.
 *
 * 2. JUST OPEN AN APP: For live TV services like Sling TV, or when
 *    the user just wants to resume whatever they were last watching.
 *    No URL needed — we just open the app at alarm time.
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
    var selectedApp by remember { mutableStateOf<StreamingApp?>(null) }
    var urlInput by remember { mutableStateOf("") }
    var parsedResult by remember { mutableStateOf<UrlParser.ParseResult?>(null) }
    var contentTitle by remember { mutableStateOf("") }
    var selectedMode by remember { mutableStateOf("choose") }  // "choose", "url", "app_only"

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
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onBack, modifier = Modifier.height(48.dp)) {
                    Text("← Back", fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.width(24.dp))
                Text(
                    text = "What Should the Alarm Play?",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = AlarmBlue
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            when (selectedMode) {
                "choose" -> {
                    // Mode selection
                    Text(
                        text = "Choose how to set your wake-up content:",
                        fontSize = 18.sp,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        // Option 1: Paste URL
                        ModeCard(
                            title = "Paste a Link",
                            description = "Go to the show in your browser, copy the URL, and paste it here. We'll figure out the rest!",
                            emoji = "🔗",
                            color = AlarmBlue,
                            onClick = { selectedMode = "url" }
                        )

                        // Option 2: Just open an app
                        ModeCard(
                            title = "Just Open an App",
                            description = "For live TV (Sling, Pluto) or to resume where you left off. We'll just open the app at alarm time.",
                            emoji = "📺",
                            color = AlarmTeal,
                            onClick = { selectedMode = "app_only" }
                        )
                    }
                }

                "url" -> {
                    // URL paste mode
                    Text(
                        text = "Paste a URL from any streaming service:",
                        fontSize = 18.sp,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // URL input field
                    BasicTextField(
                        value = urlInput,
                        onValueChange = { newValue ->
                            urlInput = newValue
                            // Auto-parse as the user types
                            parsedResult = UrlParser.parse(newValue)
                        },
                        textStyle = TextStyle(
                            fontSize = 18.sp,
                            color = TextPrimary
                        ),
                        cursorBrush = SolidColor(AlarmBlue),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(2.dp, AlarmBlue.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .background(DarkSurfaceVariant, RoundedCornerShape(8.dp))
                            .padding(16.dp),
                        decorationBox = { innerTextField ->
                            if (urlInput.isEmpty()) {
                                Text(
                                    text = "e.g., https://www.netflix.com/title/80057281",
                                    fontSize = 18.sp,
                                    color = TextSecondary.copy(alpha = 0.5f)
                                )
                            }
                            innerTextField()
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Quick test URLs
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Try:", fontSize = 14.sp, color = TextSecondary,
                            modifier = Modifier.align(Alignment.CenterVertically))
                        Button(
                            onClick = {
                                urlInput = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
                                parsedResult = UrlParser.parse(urlInput)
                            },
                            modifier = Modifier.height(36.dp)
                        ) { Text("YouTube", fontSize = 12.sp) }
                        Button(
                            onClick = {
                                urlInput = "https://www.netflix.com/title/80057281"
                                parsedResult = UrlParser.parse(urlInput)
                            },
                            modifier = Modifier.height(36.dp)
                        ) { Text("Netflix", fontSize = 12.sp) }
                        Button(
                            onClick = {
                                urlInput = "https://www.hulu.com/watch/8c2b6d15-ecdb-4c15-a0d3-6c7d07b0f323"
                                parsedResult = UrlParser.parse(urlInput)
                            },
                            modifier = Modifier.height(36.dp)
                        ) { Text("Hulu", fontSize = 12.sp) }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Show parsed result
                    if (parsedResult != null) {
                        val result = parsedResult!!
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(2.dp, Color(result.app.colorHex).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .background(DarkSurface, RoundedCornerShape(12.dp))
                                .padding(20.dp)
                        ) {
                            Column {
                                Text(
                                    text = "✓ Detected: ${result.app.displayName}",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(result.app.colorHex)
                                )
                                if (result.contentId.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Content ID: ${result.contentId}",
                                        fontSize = 16.sp,
                                        color = TextSecondary
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Name this content
                                Text("Give it a name (optional):", fontSize = 16.sp, color = TextSecondary)
                                Spacer(modifier = Modifier.height(4.dp))
                                BasicTextField(
                                    value = contentTitle,
                                    onValueChange = { contentTitle = it },
                                    textStyle = TextStyle(fontSize = 16.sp, color = TextPrimary),
                                    cursorBrush = SolidColor(AlarmBlue),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, TextSecondary.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                        .background(DarkSurfaceVariant, RoundedCornerShape(8.dp))
                                        .padding(12.dp),
                                    decorationBox = { innerTextField ->
                                        if (contentTitle.isEmpty()) {
                                            Text(
                                                text = "e.g., Stranger Things S2E1",
                                                fontSize = 16.sp,
                                                color = TextSecondary.copy(alpha = 0.5f)
                                            )
                                        }
                                        innerTextField()
                                    }
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    // Save as alarm content
                                    Button(
                                        onClick = {
                                            onContentSelected(
                                                StreamingContent(
                                                    app = result.app,
                                                    contentId = result.contentId,
                                                    title = contentTitle.ifEmpty { result.friendlyName },
                                                    launchMode = LaunchMode.DEEP_LINK
                                                )
                                            )
                                        },
                                        modifier = Modifier.height(48.dp)
                                    ) {
                                        Text("Set as Alarm Content", fontSize = 16.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp))
                                    }

                                    // Test launch
                                    if (result.contentId.isNotEmpty()) {
                                        Button(
                                            onClick = { onTestLaunch(result.app, result.contentId) },
                                            colors = ButtonDefaults.colors(containerColor = Color(result.app.colorHex)),
                                            modifier = Modifier.height(48.dp)
                                        ) {
                                            Text("Test Launch", fontSize = 16.sp,
                                                modifier = Modifier.padding(horizontal = 8.dp))
                                        }
                                    }
                                }
                            }
                        }
                    } else if (urlInput.isNotEmpty()) {
                        Text(
                            text = "Couldn't recognize that URL. Try a link from Netflix, YouTube, Hulu, Disney+, Prime Video, Max, Paramount+, Peacock, Crunchyroll, Tubi, or Pluto TV.",
                            fontSize = 16.sp,
                            color = AlarmSnoozeOrange
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Button(
                        onClick = { selectedMode = "choose" },
                        modifier = Modifier.height(44.dp)
                    ) { Text("← Back to Options", fontSize = 14.sp) }
                }

                "app_only" -> {
                    // App-only mode: just pick an app to open
                    Text(
                        text = "Pick an app to open when the alarm fires:",
                        fontSize = 18.sp,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        items(StreamingApp.allSorted()) { app ->
                            val isInstalled = installedApps.contains(app)
                            StreamingAppCard(
                                app = app,
                                isInstalled = isInstalled,
                                isSelected = selectedApp == app,
                                onClick = { selectedApp = app }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    if (selectedApp != null) {
                        val app = selectedApp!!
                        val isInstalled = installedApps.contains(app)

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(2.dp, Color(app.colorHex).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .background(DarkSurface, RoundedCornerShape(12.dp))
                                .padding(20.dp)
                        ) {
                            Column {
                                Text(
                                    text = app.displayName,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(app.colorHex)
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = if (isInstalled)
                                        "At alarm time, we'll open ${app.displayName}. It will resume whatever you were last watching."
                                    else
                                        "⚠ ${app.displayName} is not installed on this TV.",
                                    fontSize = 16.sp,
                                    color = if (isInstalled) TextSecondary else AlarmSnoozeOrange
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(
                                        onClick = {
                                            onContentSelected(
                                                StreamingContent(
                                                    app = app,
                                                    contentId = "",
                                                    title = "Open ${app.displayName}",
                                                    launchMode = LaunchMode.APP_ONLY
                                                )
                                            )
                                        },
                                        modifier = Modifier.height(48.dp)
                                    ) {
                                        Text("Set as Alarm Content", fontSize = 16.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp))
                                    }

                                    Button(
                                        onClick = { onTestLaunchAppOnly(app) },
                                        colors = ButtonDefaults.colors(containerColor = Color(app.colorHex)),
                                        modifier = Modifier.height(48.dp)
                                    ) {
                                        Text("Test Open", fontSize = 16.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp))
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Button(
                        onClick = { selectedMode = "choose" },
                        modifier = Modifier.height(44.dp)
                    ) { Text("← Back to Options", fontSize = 14.sp) }
                }
            }

            // Launch result message at the bottom
            if (launchResultMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
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

/**
 * ModeCard - A big clickable card for choosing between URL paste and app-only modes.
 */
@Composable
fun ModeCard(
    title: String,
    description: String,
    emoji: String,
    color: Color,
    onClick: () -> Unit
) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = Modifier
            .width(400.dp)
            .height(200.dp),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(16.dp)
        ),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = DarkSurface,
            focusedContainerColor = DarkSurfaceVariant
        ),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, color),
                shape = RoundedCornerShape(16.dp)
            )
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.05f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = emoji, fontSize = 36.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                fontSize = 14.sp,
                color = TextSecondary
            )
        }
    }
}
