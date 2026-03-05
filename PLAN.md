# TV Alarm Clock - Implementation Plan

---

## ⚠️ CORE PHILOSOPHY — READ THIS FIRST

**ALWAYS use SEARCH + DPAD keyboard navigation. NEVER use deep links for content.**

- Deep links are brittle, break on app updates, and cause unpredictable behavior (wrong season, pause/unpause glitches, landing on wrong screen).
- The DPAD/keyboard search method is slower to build but works reliably once tuned.
- **When it doesn't work: fix and improve the DPAD method. Do NOT switch to deep links.**
- Only exception: **Live TV channels** — even then, prefer navigating via the in-app guide/EPG, not a deep link URI.
- If search/keyboard ever gets stuck on a specific app, the answer is more tuning (better delays, different key sequence) not switching methods.

---

## Per-App Status (Last tested: Feb 2026, Onn Google TV)

### SLING TV (`com.sling`) — Partial ✅/⚠️
| What | Status | Notes |
|------|--------|-------|
| App launch | ✅ Works | Normal launch (no deep links) |
| Profile bypass | ✅ Works | Single CENTER dismisses picker |
| Live channel (Fox News) | ✅ Works | Deep link launched OK |
| Pause/unpause glitch | ⚠️ Bug | Extra pause/unpause after deep link launch. CENTER is toggling play/pause. Fix: remove extra CENTER, keep only MEDIA_PLAY |
| Search for show | ❌ Not built | Need to build search → show → episode navigation |
| Episode selection | ❌ Not built | Need to build after search works |
| **Next action** | Build DPAD search flow, fix pause glitch |

### HULU (`com.hulu.livingroomplus`) — Mostly broken ❌
| What | Status | Notes |
|------|--------|-------|
| App launch | ✅ Works | |
| Profile bypass | ✅ Works | |
| Search for show | ❌ Not attempted | Code never sends search keys |
| Episode selection | ❌ Not built | |
| **Next action** | Build full search + DPAD episode selection |

### HBO MAX / MAX (`com.wbd.stream`) — Partial ✅/⚠️
| What | Status | Notes |
|------|--------|-------|
| App launch | ✅ Works | |
| Profile bypass | ✅ Works | |
| Search | ✅ Works | Searched "Friends" correctly |
| Season navigation | ⚠️ Wrong season | Landed on Season 10 instead of chosen season |
| Episode selection | ❌ Not built | Stopped at show page, no episode picked |
| UI visibility | ⚠️ Opaque | WebView-based, no accessibility tree. Must use timed DPAD. |
| **Next action** | Fix season navigation (go to correct season #), build episode selection |

### DISNEY+ (`com.disney.disneyplus`) — Partial ✅/⚠️
| What | Status | Notes |
|------|--------|-------|
| App launch | ✅ Works | |
| Profile bypass | ✅ Works | |
| Search | ✅ Works great | Best searcher of all apps |
| Episode selection | ❌ Not built | Shows "Continue Watching", doesn't navigate to chosen episode |
| **Next action** | After search lands on show page, build DPAD nav to correct season + episode |

### PARAMOUNT+ (`com.cbs.ott`) — Partial ✅/⚠️
| What | Status | Notes |
|------|--------|-------|
| App launch | ✅ Works | |
| Profile bypass | ✅ Works | |
| Search opens | ✅ Works | |
| Extra "A" typed first | ⚠️ Bug | Types letter "A" before actual search term. Extra click before search field is ready. |
| Show selected | ✅ Works | Correct show selected |
| Season/episode nav | ❌ Not built | Scrolled to wrong show in suggestions instead of entering episodes |
| **Next action** | Fix "A" prefix bug, build episode navigation after show selected |

### PRIME VIDEO (`com.amazon.amazonvideo.livingroom`) — Partial ✅/⚠️
| What | Status | Notes |
|------|--------|-------|
| App launch | ✅ Works | |
| Search (Monk) | ✅ Works | Found show correctly |
| Season selection | ⚠️ Fails | Tried to navigate to season but didn't actually select it |
| Episode selection | ❌ Not built | Never reached episode |
| **Next action** | Fix season selection DPAD nav, build episode selection |

### NETFLIX (`com.netflix.ninja`) — Deep link only, untested with search
| **Next action** | Build search flow (currently only deep link) |

### YOUTUBE TV (`com.google.android.youtube.tv`) — Not tested
| **Next action** | Build search flow |

---

## Active Bug List (Fix in This Order)

### Bug 1: Sling Pause/Unpause Glitch (only on deep link path)
- **Root cause:** DPAD_CENTER is sent after loading which toggles play/pause. If Sling starts playing automatically, one CENTER = pause. Then MEDIA_PLAY = play. Net result: pause then unpause = glitch.
- **Fix:** In `launchSling()` — remove CENTER entirely if channel is already playing. Only send MEDIA_PLAY at the end. CENTER is only needed for profile picker on cold start.
- **File:** `ContentLaunchService.kt` → `launchSling()`

### Bug 2: Paramount+ Types "A" Before Search Term
- **Root cause:** A stray key event or DPAD press fires before the search field is focused, injecting "A" into the field.
- **Fix:** Add longer delay before typing. Clear search field first (send KEYCODE_DEL several times). Confirm field is empty before typing search term.
- **File:** `ContentLaunchService.kt` (Paramount search section)

### Bug 3: HBO Max Goes to Wrong Season (Season 10 instead of chosen)
- **Root cause:** After landing on show page, DPAD navigation ends up on the last/latest season (Season 10). TV apps pre-focus the newest season.
- **Fix:** After reaching seasons row, press DPAD_LEFT to go all the way to Season 1, then press DPAD_RIGHT (season# - 1) times to reach target season.
- **File:** `ContentLaunchService.kt`

### Bug 4: No Episode Selection Built for Any App
- **Root cause:** Code stops at the show/season page and never navigates into episodes.
- **Fix:** Build per-app episode navigation sequences (after season selected, go DOWN into episode list, navigate RIGHT to episode#, press CENTER).
- **Files:** `ContentLaunchService.kt` per-app sections

---

## Feature Backlog

### Feature A: Volume — Ramp Down to 0, Then Up to Chosen Level
- **Current:** Takes 0-100% and calls setStreamVolume() scaled to device steps. Unreliable.
- **What user wants:** Choose a specific volume number (e.g., 15 out of 100). Alarm goes ALL THE WAY DOWN to 0 first, then UP to chosen number. Same result every time regardless of starting volume.
- **Implementation:**
  1. UI: Change from percentage slider to number picker (e.g. 0–100 mapped to device steps, or just 0–100 device steps directly).
  2. Service: Send `KEYCODE_VOLUME_DOWN` × max steps to reach 0, then send `KEYCODE_VOLUME_UP` × N to reach target.
  3. Use `AudioManager.getStreamMaxVolume(STREAM_MUSIC)` to know max.
  4. Use actual key events (not setStreamVolume) so the physical TV volume responds.
- **Files:** `ContentLaunchService.kt` → `setTvVolume()`, `AlarmSetupScreen.kt` volume UI

### Feature B: Search Memory / History in Alarm App
- **What:** When user searches for a show/season/episode inside the alarm app, remember it. Next time, show a dropdown of recent searches so they don't have to retype.
- **Implementation:**
  - Save history to SharedPreferences: list of (showName, season, episode, appPackage).
  - In ContentPickerScreen, show history chips when search field is focused.
  - Max 10 entries, most recent first.
- **Files:** `ContentPickerScreen.kt`, `ContentRepository.kt`

### Feature C: In-App Navigation Flow Cleanup
- **Issue:** After saving an alarm, app jumps to unexpected screens.
- **Standard flow should be:**
  1. Home → "+ Add Alarm" → AlarmSetup → "Pick Content" → ContentPicker → select → back to AlarmSetup (content filled in) → "Save" → back to Home (alarm in list).
  2. Edit: tap alarm on Home → AlarmSetup pre-filled → edit → Save → back to Home.
- **Fix:** Audit NavController back stack. Ensure "Save" always pops back to Home, not intermediate screen.
- **Files:** `MainActivity.kt`, `AlarmSetupScreen.kt`

### Feature D: Live TV via In-App Guide (Not Deep Links)
- **Principle:** Even for live channels, navigate through the in-app guide/EPG.
- **Per-app plan:**
  - **Sling:** Launch → CENTER (profile) → navigate to Guide → DPAD to channel → CENTER to tune.
  - **YouTube TV:** Launch → Live tab → find channel by name.
  - **Paramount+ Live:** Launch → navigate to Live TV section.
- **Note:** Lower priority. Build on-demand episode selection first.

---

## Implementation Order

1. **Bug 1** — Sling pause/unpause fix (quick)
2. **Bug 2** — Paramount "A" prefix fix (quick)
3. **Bug 3** — HBO Max wrong season fix
4. **Bug 4** — Episode selection for HBO Max, Disney+, Paramount+, Prime Video, Hulu
5. **Feature A** — Volume: ramp to 0 then up to chosen level
6. **Feature B** — Search memory / history dropdown
7. **Feature C** — In-app navigation flow cleanup
8. **Feature D** — Live TV via guide

---

## Completed Work (Do Not Re-Do)

- ✅ AlarmManager scheduling (setAlarmClock)
- ✅ Wake lock + screen on (FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP)
- ✅ Profile bypass (CENTER click after app loads)
- ✅ Force-stop before relaunch (clean cold start)
- ✅ ADB over TCP for key injection (localhost:5555)
- ✅ Disney+ PIN pad entry
- ✅ Per-app load wait times (tuned to real hardware)
- ✅ MEDIA_PLAY as final safety to guarantee playback
- ✅ Foreground service to survive process freeze
- ✅ BootReceiver skeleton
- ✅ UI modularization (components split out)
- ✅ Repository pattern (AlarmRepository, ContentRepository)

---

## Key Technical Rules (Never Break These)

- **DPAD_CENTER toggles play/pause** — always use MEDIA_PLAY to force playback. Never send extra CENTER presses after content starts.
- **ADB TCP (localhost:5555)** must be enabled via `adb tcpip 5555`. All key injection goes through this.
- **React Native apps (Sling, Paramount+)** need long waits. Sling = 35s cold start minimum.
- **WebView apps (HBO Max, Hulu)** are fully opaque to accessibility. All navigation is blind DPAD with timed delays.
- **Disney+ and Paramount+** expose some accessibility info — can verify state via text.
- **Search ALWAYS beats deep links for on-demand content.** Deep links only for live TV when guide navigation isn't built yet.
