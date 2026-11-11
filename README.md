# Akoya Home: a custom Android TV / Google TV launcher

This is a home screen for your Google TV, styled like the Akoya signage. It replaces the Google TV home screen.
It's built with Kotlin and Jetpack Compose. It needs Android 9 or newer and works with the TV remote.

## What's in it

| Area | What you get |
|---|---|
| **Home** | Home is made of sections you choose and arrange: a clock and greeting, Quick apps, Continue watching, an app spotlight (for example Netflix or YouTube covers), Recent apps, an all-apps row, HDMI inputs, and shortcuts |
| **Live TV** | An IPTV player in the same style. Add free public channel lists (India, USA), any M3U playlist link, or an Xtream login. Channels from every playlist appear together, grouped by playlist and category. Favourites you can reorder, and it reopens on the last channel you watched |
| **Apps** | A grid of every app, sorted A–Z or newest first. You can hide or unhide apps. Hold OK on an app for options |
| **Shop** | Play Store, Google TV and Play Games tiles, Play Store category shortcuts, and picks from Google TV when available |
| **Settings** (the ⚙ in the top bar) | **Appearance**: six themes (Midnight Pearl, Pearl, Sumi & Gold, Zen Garden, Lotus, Harbour Blue), the background (zen pattern, gradient, your own image or solid colour), image dimming, focus zoom, app names, animations and your own logo<br>**Home layout editor**: add, move, resize, rename, hide or remove sections<br>**Clock**: 12- or 24-hour, seconds, greeting and name<br>**Screensaver**: style (ambient pearl, floating clock or photo slideshow), idle time, photo duration and a preview<br>**Apps**: hidden apps and resetting Quick apps<br>**Setup & backup**: status of the permissions, backing up and restoring settings, and a full reset |
| **Screensaver** | Runs after the idle time you set on the home screen. It can also be the TV's system screensaver (DreamService), replacing Ambient mode |

**Remote controls:** OK opens. **Hold OK** or press **Menu** for options: Quick apps, move, hide, App info, uninstall. Back returns to Home. Pressing Home while already on Home jumps back to the top.

## 1. Build the APK

**Android Studio (easiest)**
1. Open this folder: File → Open → `AkoyaHome`.
2. Let Gradle sync. Accept any suggested AGP/Gradle updates.
3. Build → Generate App Bundles or APKs → Build APK(s).
   You can also run `./gradlew assembleRelease`.
4. The APK is created at `app/build/outputs/apk/release/app-release.apk`.

**GitHub, without installing Android Studio:** push this folder to a *private* repo. The workflow in `.github/workflows/build.yml` builds the APK. Download it from the run's **Artifacts**.

The project includes a fixed signing key (`app/akoya-home.keystore`). Every build can then install over the previous one without uninstalling first. It's fine for a personal TV. Replace it if you ever publish the app.

## 2. Connect to the TV

On the TV:
1. Turn on **Settings → System → Developer options → USB debugging** (you've done this already).
2. Find the TV's IP address in **Settings → Network & Internet → (your Wi-Fi)**.

On a laptop on the same Wi-Fi (adb is part of Android "platform-tools"):
```
adb connect <TV-IP>:5555
```
The TV then asks **Allow USB debugging?** Choose **Always allow**.

## 3. Install and set up

Run one script:
```
./tools/setup_tv.sh <TV-IP>          # Mac/Linux
tools\setup_tv.bat <TV-IP>           # Windows
```

Or run the commands yourself:
```
adb install -r app-release.apk
adb shell pm grant com.akoya.home com.android.providers.tv.permission.READ_EPG_DATA       # covers / continue watching
adb shell appops set com.akoya.home GET_USAGE_STATS allow                               # smarter Recent apps
adb shell settings put secure screensaver_components com.akoya.home/.dream.AkoyaDreamService
adb shell cmd package set-home-activity com.akoya.home/.MainActivity                     # make it the Home app
```

## 4. Test it
Press **Home** on the remote. Akoya should open. Check that:
- you can move around with the D-pad and OK opens things
- holding OK on an app shows its options
- Settings → Screensaver → Preview works

## 5. If the Home button still opens Google TV
Some Google TV builds ignore the default Home app setting. You can turn off the Google launcher. This is reversible:
```
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
```
Undo it at any time with:
```
adb shell pm enable com.google.android.apps.tv.launcherx
```
Turning it off removes Google TV's own home rows and profile switcher. Search and voice (Assistant), apps, casting and Settings keep working.
A Google system update can turn the launcher back on. If that happens, run the disable command again.

## Live TV

Open the **Live TV** tab. The first time, it asks for a channel source:

- **India (free)** and **USA (free)** add the public channel lists kept by the iptv-org project. Some of those links go dead; the list updates itself once a day.
- **Playlist link** takes the M3U link from a provider. **Xtream login** takes a server address, username and password.
- Typing long links with the remote is slow. Use the phone page instead: **TV → Live TV playlists**.

While watching:

| Remote | What it does |
|---|---|
| **Up / Down** (or CH−/CH+) | Previous / next channel in the current list |
| **OK** or **Left** | Channel list. Groups on the left, channels on the right |
| **Hold OK** on a channel (or **Menu**) | Add to favourites, or move it up or down in favourites |
| **CH+ / CH−** in the channel list | Skip a screenful of channels. Holding Up or Down also scrolls without animation |
| **Right** | Show the channel banner |
| **Back** | Close the list, or leave Live TV |

If a channel's link is dead, Live TV tries the channel's other links by itself (another quality, or the same channel in another playlist) and shows **Backup link** in the banner. A channel that fails twice in a row is dimmed and marked **Not working**, and Up/Down skips it; it clears as soon as it plays again.

To show favourite channels on Home, add the **Live TV** section in Settings → Home layout.

Logins are stored only on the TV, in the app's private storage. They are not included in the settings backup.

## Personalising with files

Put files in these folders with `adb push` (no extra permissions needed):

| What | Folder on the TV |
|---|---|
| Background images | `/sdcard/Android/data/com.akoya.home/files/wallpapers/` |
| Screensaver photos | `/sdcard/Android/data/com.akoya.home/files/screensaver/` |
| Logo (`logo.png`, transparent PNG, about 400×80) | `/sdcard/Android/data/com.akoya.home/files/branding/` |
| Settings backup | `/sdcard/Android/data/com.akoya.home/files/backup/akoya-home-config.json` |

For example:
```
adb push akoya-lobby.jpg /sdcard/Android/data/com.akoya.home/files/wallpapers/
```
Then go to Settings → Appearance → Background → **Custom image**.

## Known limits

- **App covers (for example Netflix posters):** apps share these with the TV, and Akoya can read them only after the `READ_EPG_DATA` grant above. Some apps publish only to Google's own launcher. Netflix in particular may not share. In that case the spotlight shows the app's artwork tile, and the row fills in if the app starts sharing later.
- **Google TV's own features:** its account profiles, "For you" recommendations and the Ambient mode Google Photos frame belong to Google's launcher. They aren't available here.

## Project layout
```
app/src/main/java/com/akoya/home/
  MainActivity.kt            HOME activity: key handling, package changes, Home-button handling
  data/Config.kt             all settings (JSON in SharedPreferences), plus backup and restore
  data/AppRepository.kt      installed apps, banners and icons, launching, usage-based recents
  data/ContentRepository.kt  TV provider (continue watching and app programmes), HDMI inputs
  ui/LauncherRoot.kt         tabs, backdrop, app options dialog, idle screensaver
  ui/home/HomeScreen.kt      section renderer
  ui/apps, ui/shop           Apps grid and Shop
  ui/settings/               settings tabs and the layout editor
  ui/screensaver/            ambient, clock and photo screensavers
  ui/components/             TV focus cards, buttons, dialogs, backdrop, clock helpers
  ui/theme/Theme.kt          Akoya palettes and typography
  dream/AkoyaDreamService.kt system screensaver
  livetv/LiveTvStore.kt      playlists, cached channels, favourites, last channel
  livetv/M3u.kt              M3U playlist parser (unit-tested in app/src/test)
  livetv/LivePlayer.kt       Media3 player with HLS / MPEG-TS fallback and retries
  livetv/LiveTvActivity.kt   full-screen player; LiveTvScreen.kt is the channel list and banner
  livetv/PlaylistsScreen.kt  add, refresh, switch off and remove playlists
  livetv/LiveTvLauncher.kt   the Live TV tab and the Home section
```
