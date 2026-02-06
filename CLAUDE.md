# TV Alarm Clock - Project Memory

## Project Overview
An Android TV app that wakes the TV at a scheduled time, opens a streaming service
(Netflix, YouTube, Hulu, Disney+, etc.), and auto-plays specific content. Includes
a playlist feature for queuing multiple episodes/shows across different services.

**Owner:** mcsfeb (non-programmer)
**GitHub:** https://github.com/mcsfeb/TV-Alarm-Clock (private)
**Language:** Kotlin
**UI Framework:** Jetpack Compose for TV (androidx.tv:tv-material)
**Min SDK:** 21 | **Target SDK:** 35

---

## Architecture

### How the Alarm Works (The Chain of Events)
```
1. User sets alarm time + picks content/playlist
2. AlarmManager.setAlarmClock() schedules a system-level alarm
3. At alarm time: BroadcastReceiver fires
4. WakeLock (FULL_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP) turns the screen on
5. Android TV OS auto-sends HDMI-CEC "One Touch Play" -> physical TV turns on
6. AlarmActivity launches with countdown / snooze options
7. StreamingLauncher deep-links into the target app (Netflix, YouTube, etc.)
8. If playlist: ExternalPlaylistManager tracks queue, launches next item on demand
```

### Deep Linking Strategy
Each streaming app has its own package name and URI format on Android TV:

| App | Package (Android TV) | Deep Link Format |
|-----|---------------------|-----------------|
| Netflix | `com.netflix.ninja` | `http://www.netflix.com/watch/{titleId}` + extra `source=30` |
| YouTube | `com.google.android.youtube` | `vnd.youtube:{videoId}` |
| Hulu | `com.hulu.livingroomplus` | `https://www.hulu.com/watch/{episodeId}` |
| Disney+ | `com.disney.disneyplus` | `https://www.disneyplus.com/video/{contentId}` |
| Prime Video | `com.amazon.amazonvideo.livingroom` | `https://app.primevideo.com` |
| HBO Max | `com.hbo.hbonow` | `https://play.hbomax.com/episode/{id}` |

**Important caveats:**
- Netflix requires `putExtra("source", "30")` or deep links land on the home screen
- Hulu may need force-stop before re-launching to a new deep link
- None of these are official public APIs; they can break with app updates
- Always wrap startActivity() in try-catch for ActivityNotFoundException

### Key Permissions
| Permission | Why |
|-----------|-----|
| `USE_EXACT_ALARM` | Schedule alarms at exact times (auto-granted for alarm apps) |
| `WAKE_LOCK` | Turn the TV screen on |
| `RECEIVE_BOOT_COMPLETED` | Re-register alarms after device reboot |
| `INTERNET` | Fetch thumbnails and content metadata |

### Playlist System (Two Layers)
1. **Internal playback** - Media3/ExoPlayer for direct stream URLs (our own player)
2. **External launch queue** - Sequential deep-links into Netflix/YouTube/etc.
   - We track queue position and launch the next app when user advances
   - Persistent notification or overlay with "Next" / "Stop" controls

---

## Project Structure
```
app/src/main/java/com/mcsfeb/tvalarmclock/
  MainActivity.kt
  ui/
    theme/          - Colors, typography, dark theme
    screens/        - Home, AlarmSetup, ContentPicker, Playlist, AlarmFiring
    components/     - AlarmCard, ContentCard, PlaylistItem
  data/
    model/          - Alarm, StreamingContent, Playlist data classes
    repository/     - AlarmRepository, ContentRepository, PlaylistRepository
    local/          - Room database, DAOs
  service/
    AlarmReceiver.kt    - BroadcastReceiver (alarm fires here)
    BootReceiver.kt     - Re-registers alarms after reboot
    AlarmScheduler.kt   - AlarmManager wrapper
  player/
    PlaybackManager.kt      - Media3/ExoPlayer for direct playback
    StreamingLauncher.kt     - Deep link launcher for external apps
  navigation/
    AppNavigation.kt         - Compose navigation graph
```

---

## Development Milestones

### Milestone 1: Skeleton App with Timer
- Android TV project compiles and runs on emulator
- Single screen with a hardcoded alarm time
- When alarm fires: screen turns on, shows "ALARM!" text
- No streaming integration yet

### Milestone 2: Streaming App Launcher
- User can pick a streaming app from a list
- Deep link launches that app (e.g., open YouTube to a specific video)
- Basic error handling if app isn't installed

### Milestone 3: Full Alarm + Content Flow
- User sets a real time via UI
- Alarm fires at that time, wakes TV, launches chosen content
- Basic playlist: queue 2-3 items, launch them in sequence

---

## Conventions
- All code in Kotlin
- Compose for TV (not Leanback fragments)
- Dark theme by default (standard for TV apps)
- Room database for persistence
- AlarmManager.setAlarmClock() for scheduling (not WorkManager)
- Git: auto-commit and push after every task/milestone

---

## Known Risks
- Streaming app deep links are unofficial and can break anytime
- HDMI-CEC behavior varies wildly across TV brands
- Android 11+ "inattentive sleep" can override keep-screen-on flags
- Some TV devices have custom power management that blocks wake-up
