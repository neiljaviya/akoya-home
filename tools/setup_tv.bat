@echo off
REM Akoya Home - one-shot TV setup (Windows). Usage:
REM   tools\setup_tv.bat <TV-IP> [path\to\akoya-home.apk]
set IP=%1
set APK=%2
set PKG=com.akoya.home
if "%IP%"=="" (echo Usage: setup_tv.bat TV-IP [apk] & exit /b 1)
if "%APK%"=="" set APK=app\build\outputs\apk\release\app-release.apk
adb connect %IP%:5555
echo If the TV asks "Allow USB debugging?", choose Always allow, then
pause
adb -s %IP%:5555 install -r "%APK%"
adb -s %IP%:5555 shell pm grant %PKG% com.android.providers.tv.permission.READ_EPG_DATA
adb -s %IP%:5555 shell appops set %PKG% GET_USAGE_STATS allow
adb -s %IP%:5555 shell settings put secure screensaver_components %PKG%/.dream.AkoyaDreamService
adb -s %IP%:5555 shell cmd package set-home-activity %PKG%/.MainActivity
adb -s %IP%:5555 shell am start -n %PKG%/.MainActivity
echo Done. Press Home on the remote. If Google TV still opens, see README step 5.
