# TV Alarm Clock — Live Testing Memory File
**Last Updated:** 2026-03-03 (session 3)
**Worktree:** epic-perlman
**TV Device:** Onn Google TV 4K Pro — ADB: 192.168.1.90:5555
**Project:** C:\Users\Mcsfe\OneDrive\Desktop\TV Alarm Clock
**GitHub Branch:** claude/epic-perlman

---

## ⚡ QUICK RECONNECT TO TV
```
"F:/Android tv alarm/Sdk/platform-tools/adb.exe" connect 192.168.1.90:5555
```
**NOTE: F drive must be connected (USB or external drive) for ADB + builds to work.**

## 🏗️ BUILD + INSTALL (run in Windows terminal with F drive connected)
```
cd "C:\Users\Mcsfe\OneDrive\Desktop\TV Alarm Clock\.claude\worktrees\epic-perlman"
gradlew assembleDebug
"F:\Android tv alarm\Sdk\platform-tools\adb.exe" -s 192.168.1.90:5555 install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## 🧪 HOW TO TRIGGER A TEST WITHOUT WAITING FOR ALARM TIME
Use ADB am startservice to fire ContentLaunchService directly:
```
"F:\Android tv alarm\Sdk\platform-tools\adb.exe" -s 192.168.1.90:5555 shell am start-foreground-service ^
  -n com.mcsfeb.tvalarmclock/.service.ContentLaunchService ^
  --es PACKAGE_NAME "com.wbd.stream" ^
  --es DEEP_LINK_URI "https://play.max.com/search?q=Friends" ^
  --ei VOLUME 50 ^
  --es EXTRA_season "2" ^
  --es EXTRA_episode "5"
```
**Note: season/episode extras now use the `EXTRA_` prefix (e.g. `--es EXTRA_season "2"`).**

---

## 📋 CURRENT STATUS (2026-03-03 session 3)

### What was fixed this session:
1. ✅ **Season/Episode data flow fixed** — AlarmActivity and MainActivity now pass
   `season` and `episode` as extras to ContentLaunchService (was emptyMap() before).
2. ✅ **Episode navigation added** to all show-based apps via `navigateToEpisode()`:
   - HBO Max, Disney+, Paramount+, Prime Video, Hulu
3. ✅ **Disney+ 'a' timing fix** — 800ms stabilization delay before DPAD typing
4. ✅ **Pushed to GitHub** (branch: claude/epic-perlman, commit: 0b7deaa)

### Still needs (requires F drive + build + install):
- [ ] Build APK and install on TV
- [ ] Test HBO Max: specific episode (e.g. Friends S2E5)
- [ ] Test Disney+: specific episode (e.g. Suite Life S1E3)
- [ ] Test Paramount+: specific episode (e.g. Blue Bloods S3E2)
- [ ] Test Prime Video: specific episode (e.g. The Boys S2E1)
- [ ] Test Hulu: specific episode
- [ ] Tune DOWN/RIGHT counts in navigateToEpisode() based on results

---

## 🔑 KEY FINDINGS FROM ALL TESTING

### Input Methods Per App:
| App | Input Method | Why |
|-----|-------------|-----|
| HBO Max | `adb shell input text` | WebView-based keyboard |
| Paramount+ | `adb shell input text` | WebView-based keyboard |
| Disney+ | DPAD grid navigation | Native keyboard — input text FAILS |
| Hulu | Search URL deep link | Direct to search results |
| Prime Video | No typing needed | Search URL pre-populates results |
| Sling TV | No input needed | Auto-plays last channel |
| Netflix | No input needed | Deep link with source=30 auto-plays |

### Deep Link Formats (TESTED):
```
HBO Max:       https://play.max.com/search?q=Friends
Paramount+:    https://www.paramountplus.com/search/?q=Blue+Bloods
Prime Video:   https://app.primevideo.com/search?phrase=The+Boys
Disney+:       https://www.disneyplus.com/search?q=Moana
Hulu:          https://www.hulu.com/search?q=Paradise
Netflix:       http://www.netflix.com/watch/{titleId}  + extra source=30
Sling:         Normal launch only (deep links broken)
```

### Episode Navigation Logic (NEW — session 3):
Shared `navigateToEpisode(season, episode, appTag)` in ContentLaunchService.kt:
```
S1E1  → CENTER only (plays default — no navigation)
S1EN  → DOWN×2 (skip play button + season row, land on episode list)
         RIGHT×(N-1) → CENTER (play episode N)
SMEN  → DOWN×1 (to season selector row)
         RIGHT×(M-1) (to season M) → CENTER → wait 2s
         DOWN×1 (to episode list)
         RIGHT×(N-1) → CENTER (play episode N)
```
**These counts are ESTIMATES. Tune after testing if wrong.**
Layout assumed (common to HBO Max, Disney+, P+, PV, Hulu TV apps):
```
  [▶ Play / Continue Watching]   ← initial focus after opening show
  [Season 1] [Season 2] [...]    ← season selector row (1 DOWN)
  [Ep1 card] [Ep2 card] [...]   ← episode list (1 DOWN from season row)
```

### Disney+ Keyboard Layout (7 cols × 6 rows):
```
Row 0: a b c d e f g
Row 1: h i j k l m n
Row 2: o p q r s t u
Row 3: v w x y z 1 2
Row 4: 3 4 5 6 7 8 9
Row 5: 0
```
- Always starts focused on 'a' (row=0, col=0)
- Search URL BYPASSES PIN screen entirely
- 800ms stabilization delay added before typing (prevents stray 'a')
- From last typed letter: RIGHT×(7-lastCol) = first result card

### Navigation Summary Per App (after show detail page opens):
- **HBO Max search**: CENTER(profile) → input text → RIGHT×6 → CENTER(show) → navigateToEpisode
- **Paramount+ search**: CENTER(profile) → input text → RIGHT×6 → CENTER(show) → navigateToEpisode
- **Prime Video search**: RIGHT×6 → DOWN → CENTER(show) → navigateToEpisode
- **Disney+ search**: DPAD-type query → RIGHT×(7-col) → CENTER(show) → navigateToEpisode
- **Hulu search**: CENTER(profile) → CENTER(show) → navigateToEpisode
- **Sling**: CENTER(profile) → MEDIA_PLAY (no episode nav — live TV)
- **Netflix**: deep link + source=30 (auto-plays, no nav needed)

### ADB Verification Commands:
```bash
# Check if something is playing
adb -s 192.168.1.90:5555 shell dumpsys media_session | grep -A3 "state=PlaybackState"

# Get UI hierarchy (for debugging navigation)
adb -s 192.168.1.90:5555 shell uiautomator dump /data/local/tmp/ui.xml
adb -s 192.168.1.90:5555 pull /data/local/tmp/ui.xml

# Screenshot (blocked by DRM on Sling)
adb -s 192.168.1.90:5555 shell screencap /data/local/tmp/screen.png
adb -s 192.168.1.90:5555 pull /data/local/tmp/screen.png

# Watch logs in real time
adb -s 192.168.1.90:5555 logcat -s ContentLaunchSvc:V AdbShell:V

# Force-stop an app
adb -s 192.168.1.90:5555 shell am force-stop com.wbd.stream
```

---

## 📁 KEY FILES
| File | Purpose |
|------|---------|
| `service/ContentLaunchService.kt` | All app launch recipes + navigateToEpisode() |
| `service/AdbShell.kt` | ADB TCP client — sends key events and shell commands |
| `ui/screens/HomeScreen.kt` | Main screen with alarm list (DPAD fix is here) |
| `ui/screens/AlarmSetupScreen.kt` | Alarm creation flow |
| `ui/components/AlarmCard.kt` | Individual alarm card with TEST button |
| `ui/screens/AlarmActivity.kt` | Alarm firing — passes S/E extras to ContentLaunchService |
| `MainActivity.kt` | Test launch path — also passes S/E extras |

---

## 🐛 BUG TRACKER

### Fixed in Prior Sessions:
- [x] Paramount+ and Prime Video had no dedicated launch methods
- [x] Disney+ didn't use search URL (ignored deepLink param)
- [x] HBO Max didn't support search URL mode
- [x] DPAD focus jumps to top of screen after saving alarm
- [x] Volume only set AudioManager, not HDMI-CEC

### Fixed Session 3 (2026-03-03):
- [x] Season/episode NOT passed from AlarmActivity to ContentLaunchService
- [x] Season/episode NOT passed from test launch in MainActivity
- [x] No episode navigation after show page opened (always played default)
- [x] Disney+ stray 'a' at start of typing (800ms stabilization delay added)

### Needs Testing After Build:
- [ ] HBO Max: Does navigateToEpisode reach correct episode?
- [ ] Disney+: Does episode nav work? Is 'a' issue fixed?
- [ ] Paramount+: Does episode nav work?
- [ ] Prime Video: Does episode nav work?
- [ ] Hulu: Does episode nav work?
- [ ] Are DOWN/RIGHT counts right? If not, report what happened and I'll fix.

---

## 🔄 TEST PROCEDURE (episode navigation testing)
1. Connect F drive + connect ADB: `connect 192.168.1.90:5555`
2. Build + install latest APK from worktree
3. In the TV app: Add alarm → pick streaming service → search for show → **pick specific season AND episode**
4. Press TEST button on the alarm card
5. Watch logs: `logcat -s ContentLaunchSvc:V`
6. Verify: Did it open the right show AND navigate to the right episode?
7. If wrong: tell me what the log showed and what the TV displayed

---

## 📊 TEST RESULTS LOG

### HBO Max — Friends S_E_ (search)
- Search working: ✅ (session 2)
- Episode navigation: ⏳ Not yet tested (session 3 added feature)

### Sling TV — Fox News Live
- Status: ✅ Confirmed working (Feb 2026)
- Method: Normal launch → CENTER(profile) → MEDIA_PLAY

### Paramount+ — Blue Bloods (search)
- Search working: ✅ (session 2)
- Episode navigation: ⏳ Not yet tested

### Prime Video — The Boys (search)
- Search working: ✅ (Feb 2026)
- Episode navigation: ⏳ Not yet tested

### Disney+ — The Suite Life on Deck (search)
- Show found: ✅ (session 2)
- Episode navigation: ⏳ Not yet tested

### Hulu — Paradise (search)
- Show found: ✅ (session 2)
- Episode navigation: ⏳ Not yet tested

---

## 📝 HOW TO RESUME THIS TASK
1. Connect F drive (has Android SDK at F:\Android tv alarm\Sdk)
2. Connect ADB to TV (192.168.1.90:5555)
3. Build APK and install (commands at top of this file)
4. Test each app — pick a SPECIFIC show + season + episode (not just the show)
5. Watch logcat for `navigateToEpisode` and `navigate to S_E_` log lines
6. If DOWN count wrong: report what happened → fix in `navigateToEpisode()` in ContentLaunchService.kt
7. After fixing: rebuild, install, re-test
8. When all tests pass: git commit + push to GitHub
