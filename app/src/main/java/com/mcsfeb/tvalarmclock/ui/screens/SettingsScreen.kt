package com.mcsfeb.tvalarmclock.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcsfeb.tvalarmclock.service.AlarmAccessibilityService
import com.mcsfeb.tvalarmclock.ui.components.TVButton
import com.mcsfeb.tvalarmclock.ui.theme.AlarmActiveGreen
import com.mcsfeb.tvalarmclock.ui.theme.AlarmBlue
import com.mcsfeb.tvalarmclock.ui.theme.DarkSurface
import com.mcsfeb.tvalarmclock.ui.theme.TextPrimary
import com.mcsfeb.tvalarmclock.ui.theme.TextSecondary

@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    
    var smartLaunchEnabled by remember {
        mutableStateOf(prefs.getBoolean("smart_launch_enabled", true))
    }

    val isServiceRunning = AlarmAccessibilityService.isRunning()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkSurface)
            .padding(32.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TVButton(
                text = "Back",
                color = AlarmBlue,
                compact = true,
                onClick = onBack
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                "Settings",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Smart Launch Toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Enable Smart Launch",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Uses Deep Links and Automation to open specific episodes and live channels.",
                    fontSize = 14.sp,
                    color = TextSecondary
                )
            }
            
            Switch(
                checked = smartLaunchEnabled,
                onCheckedChange = {
                    smartLaunchEnabled = it
                    prefs.edit().putBoolean("smart_launch_enabled", it).apply()
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AlarmActiveGreen,
                    checkedTrackColor = AlarmActiveGreen.copy(alpha = 0.5f)
                )
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Accessibility Permission Info
        if (smartLaunchEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF222222))
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        "Automation Status",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isServiceRunning) AlarmActiveGreen else Color.Red
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (isServiceRunning) 
                            "✓ Accessibility Service is running. Smart Launch is fully active."
                        else 
                            "⚠ Accessibility Service is NOT running. Automation will fail. Please enable 'TV Alarm Clock' in Android Accessibility settings.",
                        color = TextSecondary
                    )
                    
                    if (!isServiceRunning) {
                        Spacer(modifier = Modifier.height(12.dp))
                        TVButton(
                            text = "Open Accessibility Settings",
                            color = AlarmBlue,
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        )
                    }
                }
            }
        }
    }
}
