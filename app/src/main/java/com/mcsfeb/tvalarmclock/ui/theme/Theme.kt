package com.mcsfeb.tvalarmclock.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Dark color scheme - standard for TV apps (you watch TV in the dark!)
private val TVDarkColorScheme = darkColorScheme(
    primary = AlarmBlue,
    secondary = AlarmTeal,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onPrimary = DarkBackground,
    onSecondary = DarkBackground,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = AlarmFiringRed
)

@Composable
fun TVAlarmClockTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = TVDarkColorScheme,
        content = content
    )
}
