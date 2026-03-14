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

## Per-App Status (Last tested: March 2026, Onn Google TV)

### SLING TV (`com.sling`) — Partial ✅/⚠️
| What | Status | Notes |
|------|--------|-------|
| App launch | ✅ Works | Normal launch (no deep links) |
| Profile bypass | ✅ Works | CENTER→MEDIA_PLAY, 100ms delay (was 1000ms) |
| Live channel playback | ✅ Works | MEDIA_PLAY resumes last channel |
| Pause/unpause glitch | ✅ Fixed | Reduced delay between CENTER and MEDIA_PLAY to 100ms |
| Search for show | ❌ Not built | Need to build search → show → episode navigation |
| Episode selection | ❌ Not built | Need to build after search works |
| **Next action** | Build DPAD search flow for VOD content |

### HULU (`com.hulu.livingroomplus`) — Built, needs live testing ⚠️
| What | Status | Notes |
|------|--------|-------|
| App launch | ✅ Works | |
| Profile bypass | ✅ Works | |
| Search | ✅ Built | typePvKeyboard (5 chars), sidebar nav |
| Season navigation | ✅ Built | LEFT to sidebar, DOWN×(season-1) |
| Episode selection | ✅ Built | RIGHT to list, DOWN×(episode-1) |
| Live test confirmed | ⚠️ Unverified | DRM blocks screenshot. Logic looks correct but needs manual check. |
| **Next action** | Live test with known show, verify correct episode plays |

### HBO MAX / MAX (`com.wbd.stream`) — ✅ WORKING
| What | Status | Notes |
|------|--------|-------|
| App launch | ✅ Works | |
| Profile bypass | ✅ Works | |
| Search | ✅ Works | typeTextViaAdb (standard text field) |
| Season navigation | ✅ Fixed | LEFT×20 → S1, then RIGHT×(season-1). Confirmed Friends S3. |
| Episode selection | ✅ Works | DOWN from season row, RIGHT×(episode-1). Friends S3E2 confirmed. |
| **Next action** | — (working) |

### DISNEY+ (`com.disney.disneyplus`) — ✅ WORKING
| What | Status | Notes |
|------|--------|-------|
| App launch | ✅ Works | |
| Profile bypass | ✅ Works | |
| Search | ✅ Works great | Best searcher of all apps |
| Season navigation | ✅ Works | RIGHT×(season-1) from S1, CENTER to select. |
| Episode selection | ✅ Works | DOWN×(episode-1) from episode list. Mandalorian S2E3 confirmed. |
| **Next action** | — (working) |

### PARAMOUNT+ (`com.cbs.ott`) — Partial ✅/⚠️
| What | Status | Notes |
|------|--------|-------|
| App launch | ✅ Works | |
| Profile bypass | ✅ Works | CENTER selects first profile |
| Search | ✅ Works (with fix) | Now adds DEL×10 before typing to clear stray 'A' |
| Show selected | ✅ Works | Correct show card selected after RIGHT×6 + DOWN×1 |
| Season/episode nav | ❌ Not built | After show page opens, no season/episode navigation implemented |
| **Next action** | Build season/episode nav inside P+ show detail page |

### PRIME VIDEO (`com.amazon.amazonvideo.livingroom`) — Partial ✅/⚠️
| What | Status | Notes |
|------|--------|-------|
| App launch | ✅ Works | |
| Search | ✅ Works | typePvKeyboard (5 chars) |
| Season dropdown | ✅ Built | DOWN→season dropdown, CENTER opens, UP×15, DOWN×(season-1), CENTER |
| Episode selection | ✅ Built | DOWN→episode row, RIGHT×(episode-1) |
| Live test confirmed | ⚠️ Unverified | Improved timing/nav, needs live test to confirm season/episode lands correctly |
| **Next action** | Live test with known show, verify correct episode |

### NETFLIX (`com.netflix.ninja`) — ✅ WORKING (deep link)
| What | Status | Notes |
|------|--------|-------|
| Deep link launch | ✅ Works | nflx:// deep link with source=30. Confirmed Stranger Things. |
| Bug fix | ✅ Fixed | ContentLauncher now skips search mode when content ID is present |
| **Next action** | — (working via deep link) |

### YOUTUBE TV (`com.google.android.youtube.tv`) — Not tested
| **Next action** | Build search/content launch flow |

---

## Active Bug List

### Bug 1: Sling Pause/Unpause Glitch — ✅ FIXED (March 2026)
- Reduced CENTER→MEDIA_PLAY delay from 1000ms to 100ms. Glitch now imperceptible.

### Bug 2: Paramount+ Types "A" Before Search Term — ✅ FIXED (March 2026)
- Added DEL×10 before typing to clear any stray character in the search field.

### Bug 3: HBO Max Wrong Season — ✅ FIXED (March 2026)
- LEFT×20 + RIGHT×(season-1) working. Confirmed Friends S3E2.

### Bug 4: Episode Selection — ✅ FIXED for HBO Max, Disney+. ⚠️ Unverified for Hulu, Prime Video.
- HBO Max: DOWN→season row, LEFT×20→S1, RIGHT×(season-1), DOWN→episodes, RIGHT×(episode-1). CONFIRMED.
- Disney+: RIGHT×(season-1), CENTER select, DOWN→episodes, DOWN×(episode-1). CONFIRMED.
- Hulu: Built but not live-verified. DPAD sequence in `launchHuluWithSearch`.
- Prime Video: Built but not live-verified. Season dropdown + RIGHT nav in `launchPrimeVideoWithSearch`.
- Paramount+: Season/episode nav NOT YET BUILT. Opens show page only.

### Bug 5: Paramount+ Season/Episode Nav Missing
- After search selects correct show → opens show detail page → no season/episode nav
- Need: inspect P+ show page DPAD structure, then build nav sequence
- **File:** `ContentLaunchService.kt` → `launchParamountWithSearch()`

---

## Feature Backlog

### Feature A: Volume — Ramp Down to 0, Then Up to Chosen Level — ✅ DONE (March 2026)
- `setTvVolume(n)` now ramps ALL THE WAY DOWN to 0, then presses VOLUME_UP exactly N times.
- No scaling. Volume=10 = exactly 10 button presses from 0. Consistent every time.
- UI: ±1 step buttons (was ±5). Default 15. Shows "steps" label.
- TV reports 25 max steps. Setting 10 = 10/25 = ~40% which is comfortable morning volume.

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

1. ~~**Bug 1**~~ — Sling pause/unpause fix ✅ DONE
2. ~~**Bug 2**~~ — Paramount "A" prefix fix ✅ DONE
3. ~~**Bug 3**~~ — HBO Max wrong season fix ✅ DONE
4. **Bug 4/5** — Episode selection: HBO Max ✅, Disney+ ✅, Hulu ⚠️ unverified, Prime Video ⚠️ unverified, Paramount+ ❌ not built
5. ~~**Feature A**~~ — Volume: ramp to 0 then exact steps ✅ DONE
6. **Feature B** — Search memory / history dropdown
7. **Feature C** — In-app navigation flow cleanup
8. **Feature D** — Live TV via guide

**Current Priority:**
1. Live-test Hulu episode nav (needs manual verify)
2. Live-test Prime Video episode nav (needs manual verify)
3. Build Paramount+ season/episode nav
4. Feature B: Search memory
5. Feature C: Navigation flow cleanup

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
