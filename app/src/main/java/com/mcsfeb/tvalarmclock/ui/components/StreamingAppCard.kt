package com.mcsfeb.tvalarmclock.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import com.mcsfeb.tvalarmclock.data.model.StreamingApp

/**
 * StreamingAppCard - A clickable card showing a streaming service with its REAL app icon.
 *
 * Loads the actual icon from the installed app (PackageManager.getApplicationIcon).
 * So if Netflix is installed, you see the real Netflix "N" icon.
 * If the app isn't installed, it shows just the name with the brand color.
 *
 * On Android TV, the user navigates cards with the D-pad remote.
 * Focused cards scale up and get a bright white border.
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
    val context = LocalContext.current

    // Load the real app icon from the device
    val appIcon: Drawable? = remember(app) {
        try {
            // Try main package first, then alternates
            val packages = listOf(app.packageName) + app.altPackageNames
            var icon: Drawable? = null
            for (pkg in packages) {
                try {
                    icon = context.packageManager.getApplicationIcon(pkg)
                    break
                } catch (e: Exception) {
                    // Try next package
                }
            }
            icon
        } catch (e: Exception) {
            null
        }
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(180.dp)
            .height(130.dp),
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(12.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = brandColor.copy(alpha = 0.15f),
            focusedContainerColor = brandColor.copy(alpha = 0.4f),
            pressedContainerColor = brandColor.copy(alpha = 0.25f)
        ),
        border = ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(
                    1.5.dp, brandColor.copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(12.dp)
            ),
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(2.5.dp, Color.White),
                shape = RoundedCornerShape(12.dp)
            ),
            pressedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, brandColor),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1.1f
        )
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().padding(10.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Show the real app icon if available
                if (appIcon != null) {
                    val bitmap = remember(appIcon) {
                        appIcon.toBitmap(64, 64).asImageBitmap()
                    }
                    Image(
                        bitmap = bitmap,
                        contentDescription = app.displayName,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // App name
                Text(
                    text = app.displayName,
                    fontSize = if (appIcon != null) 13.sp else 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = if (appIcon != null) 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )

                // "Not Installed" label
                if (!isInstalled) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Not Installed",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
