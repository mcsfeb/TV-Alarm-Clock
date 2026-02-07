package com.mcsfeb.tvalarmclock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.mcsfeb.tvalarmclock.service.AlarmScheduler
import com.mcsfeb.tvalarmclock.ui.screens.HomeScreen
import com.mcsfeb.tvalarmclock.ui.theme.TVAlarmClockTheme
import java.text.SimpleDateFormat
import java.util.*

/**
 * MainActivity - The entry point of the TV Alarm Clock app.
 *
 * This is the first screen users see when they open the app from
 * the Android TV launcher. For Milestone 1, it shows:
 * - A live clock
 * - A button to set a test alarm (30 seconds from now)
 * - The alarm time if one is set
 */
class MainActivity : ComponentActivity() {

    private lateinit var alarmScheduler: AlarmScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        alarmScheduler = AlarmScheduler(this)

        setContent {
            TVAlarmClockTheme {
                // Track whether an alarm is currently set
                var isAlarmSet by remember { mutableStateOf(false) }
                var alarmTimeText by remember { mutableStateOf("") }

                HomeScreen(
                    onSetAlarm = {
                        // Set an alarm 30 seconds from now (for easy testing)
                        val triggerTime = System.currentTimeMillis() + 30_000
                        alarmScheduler.schedule(triggerTime, alarmId = 0)

                        // Update the UI
                        val formatter = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
                        alarmTimeText = formatter.format(Date(triggerTime))
                        isAlarmSet = true
                    },
                    onCancelAlarm = {
                        alarmScheduler.cancel(alarmId = 0)
                        isAlarmSet = false
                        alarmTimeText = ""
                    },
                    isAlarmSet = isAlarmSet,
                    alarmTimeText = alarmTimeText
                )
            }
        }
    }
}
