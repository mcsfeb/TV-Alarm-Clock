package com.mcsfeb.tvalarmclock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.mcsfeb.tvalarmclock.data.model.StreamingApp
import com.mcsfeb.tvalarmclock.ui.components.StreamingAppCard
import com.mcsfeb.tvalarmclock.ui.theme.*

/**
 * ContentPickerScreen - Where the user picks a streaming app and enters a content ID.
 *
 * Layout:
 * 1. Top: Title "Pick a Streaming App"
 * 2. Middle: Scrollable row of streaming app cards (D-pad navigable)
 * 3. Bottom: Selected app info + content ID input + Launch button
 *
 * For Milestone 2, the content ID is entered via a simple text field.
 * In future milestones, we'll add browsing/search capabilities.
 */
@Composable
fun ContentPickerScreen(
    installedApps: List<StreamingApp>,
    onLaunchApp: (StreamingApp, String) -> Unit,
    onLaunchAppOnly: (StreamingApp) -> Unit,
    onBack: () -> Unit,
    launchResultMessage: String?
) {
    var selectedApp by remember { mutableStateOf<StreamingApp?>(null) }
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
            // Header row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onBack,
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("← Back", fontSize = 16.sp)
                }

                Spacer(modifier = Modifier.width(24.dp))

                Text(
                    text = "Pick a Streaming App",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = AlarmBlue
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Streaming apps row (scrollable with D-pad)
            Text(
                text = "Use ← → arrow keys to browse, press Select to choose:",
                fontSize = 16.sp,
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

            Spacer(modifier = Modifier.height(32.dp))

            // Selected app details + launch options
            if (selectedApp != null) {
                val app = selectedApp!!
                val isInstalled = installedApps.contains(app)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 2.dp,
                            color = Color(app.colorHex).copy(alpha = 0.5f),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .background(
                            color = DarkSurface,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(24.dp)
                ) {
                    Column {
                        Text(
                            text = app.displayName,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(app.colorHex)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = app.description,
                            fontSize = 16.sp,
                            color = TextSecondary
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        if (!isInstalled) {
                            Text(
                                text = "⚠ This app is not installed on your TV. You can still test the launch — it will show an error message.",
                                fontSize = 14.sp,
                                color = AlarmSnoozeOrange
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        // Content ID input
                        Text(
                            text = "${app.contentIdLabel}:",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Simple text display for the content ID
                        // On a real TV, this would open the on-screen keyboard
                        ContentIdInput(
                            value = contentId,
                            onValueChange = { contentId = it },
                            label = "Enter ${app.contentIdLabel}"
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Launch buttons
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Launch with content ID
                            Button(
                                onClick = {
                                    if (contentId.isNotBlank()) {
                                        onLaunchApp(app, contentId)
                                    }
                                },
                                enabled = contentId.isNotBlank(),
                                modifier = Modifier.height(52.dp)
                            ) {
                                Text(
                                    text = "Launch with Content ID",
                                    fontSize = 18.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            }

                            // Launch app only (no specific content)
                            Button(
                                onClick = { onLaunchAppOnly(app) },
                                colors = ButtonDefaults.colors(
                                    containerColor = Color(app.colorHex)
                                ),
                                modifier = Modifier.height(52.dp)
                            ) {
                                Text(
                                    text = "Just Open ${app.displayName}",
                                    fontSize = 18.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                // No app selected yet
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Select a streaming app above to get started",
                        fontSize = 20.sp,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Launch result message
            if (launchResultMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
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
 * ContentIdInput - A simple TV-friendly text input field.
 *
 * On Android TV, text input typically opens the system on-screen keyboard
 * when the field is focused. This is a basic implementation that works
 * with the TV remote's D-pad navigation.
 */
@Composable
fun ContentIdInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    // For now, use pre-set test content IDs that the user can cycle through
    // In a future milestone, we'll add proper keyboard input
    val testIds = listOf(
        "dQw4w9WgXcQ",        // YouTube - Rick Astley
        "80057281",            // Netflix - Stranger Things
        "70153404",            // Netflix - Friends
        "",                     // Empty (custom)
    )
    var currentIndex by remember { mutableIntStateOf(0) }

    Column {
        // Show the current value
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TextSecondary.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .background(DarkSurfaceVariant, RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Text(
                text = if (value.isBlank()) label else value,
                fontSize = 18.sp,
                color = if (value.isBlank()) TextSecondary else TextPrimary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Quick-fill buttons for testing
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Quick test IDs:",
                fontSize = 14.sp,
                color = TextSecondary,
                modifier = Modifier.align(Alignment.CenterVertically)
            )

            Button(
                onClick = {
                    onValueChange("dQw4w9WgXcQ")
                },
                modifier = Modifier.height(36.dp)
            ) {
                Text("YouTube Test", fontSize = 12.sp)
            }

            Button(
                onClick = {
                    onValueChange("80057281")
                },
                modifier = Modifier.height(36.dp)
            ) {
                Text("Netflix Test", fontSize = 12.sp)
            }

            Button(
                onClick = {
                    onValueChange("")
                },
                modifier = Modifier.height(36.dp)
            ) {
                Text("Clear", fontSize = 12.sp)
            }
        }
    }
}
