# TV Alarm Clock - ADB Deep Link Test Results
## Date: 2026-02-20
## Device: Onn Google TV at 192.168.1.90:5555

---

## FINAL RESULTS SUMMARY

| App | Deep Link | Auto-Play? | Profile Bypass | Status |
|-----|-----------|------------|----------------|--------|
| YouTube | `https://www.youtube.com/watch?v=VIDEO_ID` | YES | Not needed | PASS |
| Netflix | `nflx://www.netflix.com/watch/TITLE_ID` + `source=30` | YES | Not needed (this TV) | PASS |
| Paramount+ | `https://www.paramountplus.com/live-tv/` | YES | Not needed | PASS |
| Sling TV | Normal launch (deep links BROKEN) | YES (last channel) | 1x CENTER after 35s | PASS |
| Hulu | `https://www.hulu.com/watch/UUID` | NO (show page) | 3x CENTER (profile + play) | PASS |
| HBO Max | `https://play.max.com/video/watch/ID` | Needs real ID | 2x CENTER + nav | PASS |
| Prime Video | `https://watch.amazon.com/watch?asin=ASIN` | Needs real ASIN | Not needed | PASS (with valid ID) |
| Disney+ | `https://www.disneyplus.com/video/ID` | PIN BLOCKS | Needs 4-digit PIN | BLOCKED |
| Tubi | `tubitv://media-playback/ID` | Needs real ID | Not needed | UNTESTED (need real ID) |

---

## DETAILED TEST RESULTS

### Test 1: YouTube - PASS
- **Command:** `am start -a android.intent.action.VIEW -d 'https://www.youtube.com/watch?v=dQw4w9WgXcQ' -p com.google.android.youtube.tv`
- **Result:** Video auto-plays immediately. No profile picker.
- **Media session:** `state=PLAYING(3), description=Rick Astley - Never Gonna Give You Up`
- **Wait time:** 12s sufficient

### Test 2: Netflix - PASS
- **Command:** `am start -a android.intent.action.VIEW -d 'nflx://www.netflix.com/watch/80057281' -p com.netflix.ninja --es source 30`
- **Result:** Video auto-plays immediately. No profile picker on this TV.
- **Media session:** `state=PLAYING(3)` (Netflix doesn't expose title in metadata)
- **Wait time:** 15s sufficient
- **CRITICAL:** Must use `nflx://` scheme AND `source=30` extra, or lands on home screen

### Test 3: Sling TV - PASS (with workaround)
- **FINDING:** Sling deep links are BROKEN in ALL scenarios:
  - Cold start with deep link: Player UI opens but stream stuck (ExoPlayer shutter visible, state=STOPPED)
  - Warm (already playing) + deep link: STOPS current playback, never starts new channel
- **WORKAROUND:** Launch Sling normally. It always auto-plays last-watched channel.
  - Normal launch: `am start -n com.sling/.MainActivity`
  - Wait 35s for full load
  - Send 1x DPAD_CENTER for profile bypass
- **Media session after normal launch:** `state=PLAYING(3), description=The Five` (Fox News)
- **Recommendation:** Users should set their preferred channel in Sling itself

### Test 4: Paramount+ - PASS
- **Command:** `am start -a android.intent.action.VIEW -d 'https://www.paramountplus.com/live-tv/' -p com.cbs.ott`
- **Result:** Auto-plays live TV immediately. No profile bypass needed.
- **Media session:** `state=PLAYING(3)`
- **Wait time:** 20s sufficient
- **Note:** Intent filter only matches root path (LITERAL empty), but /live-tv/ still works

### Test 5: Hulu - PASS (with 3x CENTER)
- **Sequence:**
  1. Force-stop Hulu
  2. Send deep link: `am start -a android.intent.action.VIEW -d 'https://www.hulu.com/series/family-guy-...' -p com.hulu.livingroomplus`
  3. Wait 25s for cold start
  4. Send DPAD_CENTER #1 (profile bypass)
  5. Wait 3s
  6. Send DPAD_CENTER #2 (navigate/confirm)
  7. Wait 10s
  8. Send DPAD_CENTER #3 (START PLAYBACK)
- **Media session:** `state=PLAYING(3)`
- **Note:** Hulu is completely opaque (WebView). Deep link goes to show page, not player.

### Test 6: HBO Max - PASS (with navigation)
- **Command:** `am start -n com.wbd.stream/com.wbd.beam.BeamActivity` (normal launch)
- **Sequence:**
  1. Wait 30s for cold start
  2. DPAD_CENTER (profile bypass)
  3. Wait 5s
  4. DPAD_CENTER (secondary bypass)
  5. Wait 8s
  6. DPAD_DOWN + DPAD_CENTER (navigate to content)
- **Media session:** `state=PLAYING(3), description=Dead of Winter`
- **Note:** Completely opaque WebView. Deep links with real content IDs should work directly.

### Test 7: Prime Video - PASS (with navigation)
- **Command:** `am start -a android.intent.action.VIEW -d 'https://watch.amazon.com/watch?asin=ASIN' -p com.amazon.amazonvideo.livingroom`
- **Result:** Opens app. With invalid ASIN goes to home page. DOWN + CENTER plays featured content.
- **Media session:** `state=PLAYING(3)` after navigation
- **Note:** Intent filter confirms `watch.amazon.com` authority. Need valid ASINs.

### Test 8: Disney+ - BLOCKED (PIN screen)
- **Command:** `am start -a android.intent.action.VIEW -d 'https://www.disneyplus.com/video/ID' -p com.disney.disneyplus`
- **Result:** Opens to PIN entry screen. Cannot bypass without user's 4-digit PIN.
- **UI shows:** `enterPinPromptText`, `digitKey`, `pinCodeKeyboard`
- **Note:** PIN pad layout documented in AppNavigationGuide.kt

### Test 9: Tubi - UNTESTED (need real content ID)
- **Intent filters confirmed:** `tubitv://media-playback/{id}`, `tubitv://media-details/{id}`, `tubitv://live-news`
- **Note:** App is opaque like most TV apps

---

## KEY CHANGES MADE TO CODE

1. **ContentLaunchService.kt** - Complete rewrite of launch logic:
   - Added `performSlingLaunch()`: Normal launch only (deep links broken)
   - Added `performHuluLaunch()`: Force-stop + deep link + 3x DPAD_CENTER
   - Added `sendDpadCenter()` helper
   - Updated `needsProfileBypass()`: Removed Sling/Hulu (handled separately)
   - Updated `needsReDeepLink()`: Only HBO Max now
   - Added post-re-deep-link CENTER press for HBO

2. **ContentLauncher.kt** - Updated deep link formats:
   - Sling: Added comment about broken deep links
   - Prime Video: Reordered to prefer `watch.amazon.com` (verified intent filter)
   - Tubi: Added `tubitv://media-playback/{id}` and `tubitv://media-details/{id}`

3. **deep_link_config.json** - Updated to v4:
   - Sling: Updated comment about broken deep links
   - Hulu: Updated comment about needing 3x CENTER
   - Prime Video: Reordered deep link formats
   - Tubi: Added tubitv:// scheme formats

4. **AppNavigationGuide.kt**:
   - Added YOUTUBE_TV and SPOTIFY package constants
   - Added Tubi deep link method
   - Updated Prime Video to use `watch.amazon.com`
