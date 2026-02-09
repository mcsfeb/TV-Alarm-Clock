# TV Alarm Clock - Implementation Plan

## Three Features to Build

### 1. Wake-on-Alarm (Turn on TV from standby)
### 2. Modularize the Project (Separate files for Clock UI, Alarm Logic, TV Intent handlers)
### 3. Better Deep Link Discovery (Find deep links automatically so launches work flawlessly)

---

## Feature 1: Wake-on-Alarm

**What we already have (it's mostly done!):**
- `AlarmReceiver.kt` already grabs a `FULL_WAKE_LOCK` with `ACQUIRE_CAUSES_WAKEUP` - this turns the screen on
- `AlarmActivity.kt` already sets `FLAG_KEEP_SCREEN_ON`, `setShowWhenLocked(true)`, `setTurnScreenOn(true)`
- `AlarmScheduler.kt` uses `setAlarmClock()` which wakes from Doze mode
- Android TV OS automatically sends HDMI-CEC "One Touch Play" when the device wakes up, which turns on the physical TV

**What needs improvement:**
- `BootReceiver.kt` is a **placeholder** - alarms are lost after reboot! Need to make it actually reload and reschedule alarms
- Add a `SCHEDULE_EXACT_ALARM` permission for Android 12+ devices that need it
- Add `FOREGROUND_SERVICE` for Android 14+ requirements (some devices need a foreground service to reliably wake)

**Plan:**
1. **Fix BootReceiver** - Read alarms from SharedPreferences and reschedule them all
2. **Add WakeUpHelper utility** - Centralize all wake-up logic (WakeLock + screen on + keep awake) into one file instead of splitting it across AlarmReceiver and AlarmActivity
3. **Add permission check** - Check if `SCHEDULE_EXACT_ALARM` is needed on Android 12+ and guide user to grant it

---

## Feature 2: Modularize the Project

**Current problem - some files do too much:**
- `MainActivity.kt` (227 lines) - Navigation, alarm CRUD, scheduling, content saving, SharedPreferences - all in one file
- `HomeScreen.kt` (561 lines) - Clock UI, time picker, alarm list, alarm card, TVButton, and helper functions all together
- `ContentPickerScreen.kt` (909 lines) - Biggest file! App picker, channel guide, search, season/episode picker, manual entry all in one

**Files that are already well-organized:**
- `StreamingLauncher.kt` - Just does launching (good)
- `AlarmScheduler.kt` - Just does scheduling (good)
- `AlarmReceiver.kt` - Just receives alarms (good)
- `StreamingApp.kt` - Just data definitions (good)
- `ProfileAutoSelector.kt` - Just auto-clicking (good)

**Plan - split into these new files:**

```
ui/
  components/
    StreamingAppCard.kt     (KEEP - already exists)
    TVButton.kt             (NEW - extract from HomeScreen)
    AlarmCard.kt            (NEW - extract from HomeScreen)
    ClockDisplay.kt         (NEW - extract clock + AM/PM from HomeScreen)
    TimePicker.kt           (NEW - extract time picker from HomeScreen)
    ModeCard.kt             (NEW - extract from ContentPickerScreen)
    ChannelList.kt          (NEW - extract from ContentPickerScreen)
    SearchResultCard.kt     (NEW - extract from ContentPickerScreen)
    EpisodeCard.kt          (NEW - extract from ContentPickerScreen)
  screens/
    HomeScreen.kt           (SLIM DOWN - just assembles components)
    ContentPickerScreen.kt  (SLIM DOWN - just navigation + state)
    AlarmActivity.kt        (KEEP - already focused)

data/
  repository/
    AlarmRepository.kt      (NEW - alarm CRUD, save/load from SharedPreferences)
    ContentRepository.kt    (NEW - content save/load from SharedPreferences)

service/
  WakeUpHelper.kt           (NEW - centralize WakeLock + screen on logic)
```

**Result:** Each file does ONE thing. HomeScreen goes from 561 lines to ~150. ContentPickerScreen goes from 909 lines to ~300. MainActivity drops to ~100 lines.

---

## Feature 3: Better Deep Link Discovery

**Current approach - multiple layers already in place:**
1. **Search-based launch** (`launchWithSearch`) - Opens app and searches by show name (MOST RELIABLE)
2. **App-specific search URLs** (`getSearchDeepLink`) - Direct URL deep links to search pages (works for 12 apps)
3. **Android SEARCH intent** - Generic Android intent that some apps handle
4. **Global media search** - Android TV's built-in search system
5. **Hardcoded content IDs** (`ContentIdMapper`) - Only ~20 popular shows mapped
6. **Manual ID entry** - User types in the content ID themselves

**What's NOT working well:**
- `ContentIdMapper` only has ~20 shows - most shows won't have deep link IDs
- Channel IDs in `ChannelGuide` are guesses - no verification they work
- No way to verify if a deep link actually works before the alarm fires

**Plan - enhance the search-first approach:**
1. **Use TMDB Watch Providers API** - Already have `getWatchProviders()` but it's not used in the UI! Wire it up to show which apps have a show, so the user picks the right app
2. **Add deep link verification** - Before saving an alarm, try launching the deep link silently to see if it resolves (using `PackageManager.resolveActivity()`) - don't actually launch, just check if it would work
3. **Improve search launch as primary strategy** - Since search-based launching is the most reliable, make it the default. When user picks a show, default to SEARCH mode instead of trying to find a content ID first
4. **Add TMDB external IDs** - TMDB has an "external_ids" endpoint that can give us IMDB IDs. Some streaming apps accept IMDB IDs in their deep links
5. **Fallback chain improvement** - Add better error messages and automatic fallback: try deep link -> try search -> try app-only -> show error with tips

---

## Implementation Order

I'll do them in this order because each builds on the previous:

1. **Modularize first** - Break files apart so the codebase is clean before adding features
2. **Wake-on-Alarm** - Fix BootReceiver and centralize wake logic
3. **Deep Link Discovery** - Wire up TMDB providers, add verification, improve search

---

## Step-by-Step Breakdown

### Step 1: Extract UI Components
- Create `TVButton.kt` from HomeScreen
- Create `AlarmCard.kt` from HomeScreen
- Create `ClockDisplay.kt` from HomeScreen
- Create `TimePicker.kt` from HomeScreen
- Create `ModeCard.kt`, `ChannelList.kt`, `SearchResultCard.kt`, `EpisodeCard.kt` from ContentPickerScreen
- Update imports in HomeScreen, ContentPickerScreen

### Step 2: Create Repositories
- Create `AlarmRepository.kt` - move save/load alarm logic from MainActivity
- Create `ContentRepository.kt` - move save/load content logic from MainActivity
- Slim down MainActivity to just use repositories

### Step 3: Fix BootReceiver + WakeUpHelper
- Create `WakeUpHelper.kt` - centralize WakeLock logic from AlarmReceiver
- Fix `BootReceiver.kt` - actually reload and reschedule saved alarms
- Add SCHEDULE_EXACT_ALARM permission handling

### Step 4: Deep Link Improvements
- Wire up TMDB watch providers in ContentPickerScreen
- Add `resolveActivity()` verification in StreamingLauncher
- Add TMDB external IDs lookup
- Improve fallback chain with better error messages
- Make SEARCH mode the default when no content ID is known

### Step 5: Build, Test, Commit
- Build the project
- Install on emulator
- Test alarm flow end-to-end
- Commit and push to GitHub
