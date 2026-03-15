# TV Alarm Clock — Project Memory

## CRITICAL: Project Location
**Project has been moved to F drive.**
- NEW path: `F:\Android tv alarm\TV Alarm Clock\`
- OLD path (C drive OneDrive) has been deleted — do NOT use it
- Always open/work from: `F:\Android tv alarm\TV Alarm Clock\`

## Android Development Environment (F drive)
- Android SDK: `F:\Android tv alarm\Sdk\`
- Gradle cache: `F:\Android tv alarm\.gradle\`
- Android Studio: `F:\Android Studio\bin\studio64.exe`
- Android Studio launcher (auto-clears lock): `C:\Users\Mcsfe\OneDrive\Desktop\Launch Android Studio.bat`

## GitHub
- Repo: https://github.com/mcsfeb/TV-Alarm-Clock (private)
- Main branch: `master`
- Working branch: `claude/epic-perlman` (most recent work)

## TV / ADB
- TV IP: 192.168.1.90:5555 (Onn Google TV 4K Pro)
- ADB connect: `adb connect 192.168.1.90:5555`

## Key Files
- Launch service: `app/src/main/java/com/mcsfeb/tvalarmclock/service/ContentLaunchService.kt`
- Alarm activity: `app/src/main/java/com/mcsfeb/tvalarmclock/ui/screens/AlarmActivity.kt`
- Alarm setup UI: `app/src/main/java/com/mcsfeb/tvalarmclock/ui/screens/AlarmSetupScreen.kt`
- Deep link config: `app/src/main/assets/deep_link_config.json`

## Pending Work (as of session end Mar 2026)
Episode navigation is broken for all SEARCH mode apps. The `navigateToEpisode()` generic
function uses wrong DOWN counts. Per-app fixes still needed:
- Sling: remove MEDIA_PLAY (causes pause toggle)
- Hulu: add search URL handling (currently does nothing)
- HBO Max: DOWN counts wrong → lands on wrong season
- Disney+: DOWN counts wrong → shows "continue watching"
- Paramount+: stray 'a' typed before search + wrong DOWN counts
- Prime Video: season/episode not selected after show opens

Volume: user wants absolute CEC positioning (go to 0, then UP × target number).
Fix: change downSteps from 15 to 100 in setTvVolume().
