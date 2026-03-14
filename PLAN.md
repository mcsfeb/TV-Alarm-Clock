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
| Profile bypass | ✅ Works | CENTER→MEDIA_PLAY, 100ms delay |
| Live channel playback | ✅ Works | MEDIA_PLAY resumes last channel |
| Pause/unpause glitch | ✅ Fixed | |
| VOD search | ✅ Built | launchSlingWithSearch() — nav bar DPAD_UP → Search → typePvKeyboard |
| Live channel by name | ✅ Built | LIVE: prefix → guide nav via DPAD_UP. Needs live test. |
| **Next action** | Live test VOD search + live channel guide nav |

### HULU (`com.hulu.livingroomplus`) — Built, needs live testing ⚠️
| What | Status | Notes |
|------|--------|-------|
| App launch | ✅ Works | |
| Profile bypass | ✅ Works | |
| Search | ✅ Built | typePvKeyboard (5 chars), sidebar nav (LEFT→UP→CENTER) |
| Season navigation | ✅ Built | LEFT to sidebar, DOWN×(season-1) |
| Episode selection | ✅ Built | RIGHT to list, DOWN×(episode-1) |
| Timing | ✅ Increased | Cold start 30s, post-profile 12s, sidebar 2s, search open 5s |
| Live test confirmed | ⚠️ Unverified | DRM blocks screenshot. Needs manual check. |
| **Next action** | Live test with known show, verify correct episode plays |

### HBO MAX / MAX (`com.wbd.stream`) — ✅ WORKING
| What | Status | Notes |
|------|--------|-------|
| App launch | ✅ Works | |
| Profile bypass | ✅ Works | |
| Search | ✅ Works | typeTextViaAdb (standard text field) |
| Season navigation | ✅ Fixed | LEFT×20 → S1, then RIGHT×(season-1). Confirmed Friends S3. |
| Episode selection | ✅ Works | DOWN from season row, RIGHT×(episode-1). Friends S3E2 confirmed. |
| Overlay dismiss | ✅ Fixed | DPAD_DOWN after MEDIA_PLAY dismisses "You May Also Like" overlay |
| **Next action** | — (working) |

### DISNEY+ (`com.disney.disneyplus`) — ✅ WORKING (rebuilt search)
| What | Status | Notes |
|------|--------|-------|
| App launch | ✅ Works | |
| Profile bypass | ✅ Works | |
| Search | ✅ Rebuilt | KEYCODE_SEARCH opened Gemini — fixed: UP→LEFT→UP→CENTER sidebar nav |
| Keyboard | ✅ Fixed | typeDisney7Keyboard() — 7-col DPAD layout (typeTextViaAdb didn't work) |
| Season navigation | ✅ Works | RIGHT×(season-1) from S1, CENTER to select. |
| Episode selection | ✅ Works | DOWN×(episode-1) from episode list. Mandalorian S2E3 confirmed. |
| **Next action** | Live test new search path to confirm |

### PARAMOUNT+ (`com.cbs.ott`) — ✅ Fixed search navigation
| What | Status | Notes |
|------|--------|-------|
| App launch | ✅ Works | |
| Profile bypass | ✅ Works | CENTER selects first profile |
| Search open | ✅ Fixed | BACK to browse screen → input tap 62 250 (was 62 274, app updated) |
| Keyboard | ✅ Fixed | DPAD_DOWN activates focus → typePvKeyboard (typeTextViaAdb didn't work) |
| Show selected | ✅ Works | RIGHT×(6-endCol) + DOWN×1 + CENTER |
| Season/episode nav | ❌ Not built | P+ doesn't expose season picker via DPAD from show detail |
| **Next action** | Live test search. Season/episode nav needs investigation of P+ detail page. |

### PRIME VIDEO (`com.amazon.amazonvideo.livingroom`) — Partial ✅/⚠️
| What | Status | Notes |
|------|--------|-------|
| App launch | ✅ Works | |
| Search | ✅ Works | typePvKeyboard (5 chars) |
| Season dropdown | ✅ Fixed | DOWN×3 + UP×1 → season dropdown (was DOWN×1, hit subscription pages) |
| Episode selection | ✅ Fixed | DOWN×2 → episode row (was DOWN×1) |
| Live test confirmed | ⚠️ Unverified | Needs live test with show that IS in Prime subscription |
| **Next action** | Live test with known included show, verify season/episode lands correctly |

### NETFLIX (`com.netflix.ninja`) — ✅ WORKING (deep link)
| What | Status | Notes |
|------|--------|-------|
| Deep link launch | ✅ Works | nflx:// deep link with source=30. Confirmed Stranger Things. |
| **Next action** | — (working via deep link) |

### YOUTUBE TV (`com.google.android.youtube.tv`) — Not tested
| **Next action** | Build search/content launch flow |

---

## Active Bug List

### Bug 1: Sling Pause/Unpause Glitch — ✅ FIXED (March 2026)
- Reduced CENTER→MEDIA_PLAY delay to 100ms. Glitch now imperceptible.

### Bug 2: Paramount+ Types "A" Before Search Term — ✅ FIXED (March 2026)
- Added DEL×10 before typing to clear any stray character in the search field.

### Bug 3: HBO Max Wrong Season — ✅ FIXED (March 2026)
- LEFT×20 + RIGHT×(season-1) working. Confirmed Friends S3E2.

### Bug 4: Episode Selection — ✅ FIXED for HBO Max, Disney+. ⚠️ Unverified for Hulu, Prime Video.
- HBO Max: CONFIRMED working (Friends S3E2).
- Disney+: CONFIRMED working (Mandalorian S2E3). Search rebuilt March 2026.
- Hulu: Built, timing increased (30s cold start, 12s post-profile). Needs live verify.
- Prime Video: Rebuilt (DOWN×3+UP×1 for season dropdown). Needs live verify.
- Paramount+: Season/episode nav NOT BUILT. Show opens to continue-watching position.

### Bug 5: Paramount+ Season/Episode Nav — ⚠️ Not Built
- P+ Compose UI doesn't expose season picker elements via DPAD from show detail
- Show opens to continue-watching episode (last watched) — no episode selection

### Bug 6: Disney+ Search Opened Google Gemini — ✅ FIXED (March 2026)
- KEYCODE_SEARCH intercepted by Google TV → opened Gemini, not Disney+ search
- FIX: UP→LEFT→UP→CENTER sidebar nav. typeDisney7Keyboard() for 7-col keyboard.

### Bug 7: Paramount+ Search Tap Broken After App Update — ✅ FIXED (March 2026)
- Old: `input tap 62 274` → landed below search icon after P+ app updated its layout
- FIX: BACK to browse screen first, then `input tap 62 250`. DPAD_DOWN activates focus.
- OLD typeTextViaAdb() → didn't work (no EditText). Now uses typePvKeyboard().

### Bug 8: HBO Max Overlay After Playback — ✅ FIXED (March 2026)
- "You May Also Like" suggestion overlay appears after episode starts
- FIX: DPAD_DOWN + MEDIA_PLAY after playback to dismiss overlay

### Bug 9: Prime Video Season Nav Hit Subscription Pages — ✅ FIXED (March 2026)
- DOWN×1 was landing on subscription buttons instead of season dropdown
- FIX: DOWN×3 + UP×1 to reliably reach season dropdown, DOWN×2 to episode row

### Bug 10: HOME Button Didn't Stop Navigation — ✅ FIXED (March 2026)
- No mechanism to stop service when user pressed HOME during navigation
- FIX: BroadcastReceiver for ACTION_CLOSE_SYSTEM_DIALOGS, checkAborted() between steps

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
4. ~~**Bug 4**~~ — Episode selection: HBO Max ✅, Disney+ ✅ (rebuilt search March 2026), Hulu ⚠️ unverified, Prime Video ⚠️ unverified
5. ~~**Feature A**~~ — Volume: ramp to 0 then exact steps ✅ DONE
6. ~~**Bug 5**~~ — Paramount+ season/episode nav: not built (P+ doesn't expose season DPAD)
7. ~~**Bug 6**~~ — Disney+ search opened Gemini ✅ FIXED (March 2026)
8. ~~**Bug 7**~~ — Paramount+ search tap broken after app update ✅ FIXED (March 2026)
9. ~~**Bug 8**~~ — HBO Max overlay ✅ FIXED (March 2026)
10. ~~**Bug 9**~~ — Prime Video season nav hit subscriptions ✅ FIXED (March 2026)
11. ~~**Bug 10**~~ — HOME button interrupt ✅ FIXED (March 2026)
12. **Feature B** — Search memory / history dropdown
13. **Feature C** — In-app navigation flow cleanup
14. **Feature D** — Live TV via guide (Sling basic structure added)

**Current Priority:**
1. Live-test Disney+ (new search path — needs verify on device)
2. Live-test Paramount+ (new search opening — needs verify on device)
3. Live-test Hulu episode nav (timing increased — needs manual verify)
4. Live-test Prime Video season nav (DOWN×3+UP×1 — needs manual verify)
5. Live-test Sling VOD search + live channel guide (new, needs verify)
6. Feature B: Search memory
7. Feature C: Navigation flow cleanup

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
- ✅ HOME button interrupt (BroadcastReceiver for ACTION_CLOSE_SYSTEM_DIALOGS)
- ✅ Disney+ search via sidebar nav (UP→LEFT→UP→CENTER) + typeDisney7Keyboard()
- ✅ Paramount+ search via browse screen BACK + input tap 62 250 + typePvKeyboard()
- ✅ HBO Max overlay dismiss (DPAD_DOWN after playback)
- ✅ Prime Video season nav (DOWN×3+UP×1 to season dropdown)
- ✅ Hulu timing increased (30s cold start, 12s post-profile)
- ✅ Sling VOD search + live channel guide navigation (launchSlingWithSearch)

---

## Key Technical Rules (Never Break These)

- **DPAD_CENTER toggles play/pause** — always use MEDIA_PLAY to force playback. Never send extra CENTER presses after content starts.
- **ADB TCP (localhost:5555)** must be enabled via `adb tcpip 5555`. All key injection goes through this.
- **React Native apps (Sling, Paramount+)** need long waits. Sling = 35s cold start minimum.
- **WebView apps (HBO Max, Hulu)** are fully opaque to accessibility. All navigation is blind DPAD with timed delays.
- **Disney+ and Paramount+** expose some accessibility info — can verify state via text.
- **Search ALWAYS beats deep links for on-demand content.** Deep links only for live TV when guide navigation isn't built yet.
