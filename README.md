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
| **Left / Right** (or CH−/CH+) | Previous / next channel in the current list |
| **OK** | Shows the channel banner. **OK** again opens the channel list |
| **Up / Down** | Shows the channel banner. **Up** or **Down** again opens the TV guide |
| **Hold OK** (or **Menu**) | Options: picture quality, favourites, categories, rename, hide |
| **Back** | Close what's open, or leave Live TV |

In the channel list: groups are on the left, channels on the right. **Hold OK** on a channel or on a group for its options. **CH+ / CH−** skip a screenful, and holding Up or Down scrolls without animation.

### Managing a big channel list

**Manage channels** (bottom of the channel list) is built for playlists with thousands of channels:

- **Groups**: every provider group with a Shown / Hidden switch, and "Hide all groups" per playlist so you can switch everything off and bring back only what you watch.
- **Your own categories**: make a category, then hold OK on any channel → *Add to a category*. Categories sit above the provider's groups and keep the order you give them.
- **Rename** channels and groups, **hide** single channels, and sort channels **A to Z**.
- **Check which channels work**: tries every link in a group, a playlist or everything, and marks the dead ones. Paid logins usually allow one connection, so those are checked one channel at a time with the picture paused; do a group at a time rather than the whole playlist.
- **Hide channels that don't work** removes marked channels from every list instead of dimming them.

### Picture quality

Hold OK while watching → **Picture quality**. The limit (1080p, 720p, 480p, 360p) applies to channels that are sent in several qualities; the player then stays at or below it, which buffers less on a weak connection. Most IPTV channels are sent in one quality only, so the same menu offers other versions of the channel (for example the SD one) when your playlists have them.

### Movies and series

The Live TV tab shows **Movies** and **Series** tiles when a playlist's provider has a library. That works for an Xtream login and for M3U links too: the account is read from the link, or from the channel links inside the playlist when the link itself doesn't show it.

**Regions.** Every film and series is filed under Hollywood, Bollywood, South, Gujarati or Rest of the world, and the chips at the top of the library switch between them (or All). The title's own markers decide first (`[TUR]`, `[HIN+TAM]`, `(GUJARATI)`), then the provider's category. Hindi dubs count as Bollywood.

**One entry per film.** Providers file the same film several times (by year, in 4K, under a genre, as multi-audio). The library shows it once, with the provider's trimmings removed from the title, and the film's page lists its **other copies** (for example the 4K one).

**Home** for the chosen region: Continue watching, My list, Recommended for you and "Because you watched…" (by genre, from what you've watched for two minutes or more), Recently added, trending, then a row per genre. Each row shows 30 titles and ends in "See all".

**Browse** (the Categories button) shows tiles for **Genres**, **By year** (each recent year, then 2010s, 2000s, 90s, 80s, Classics) and **Collections** (franchises, trending, 4K, networks). Hold OK on a collection to pin it to Home or hide it. Inside any tile, sort by Recently added, A to Z, Top rated or Newest first; hold OK on a poster adds it to My list.

**Genres** come from three places: the provider's own genre shelves, a genre field in the provider's list when it has one, and each film's detail page as you open it. For the films still untagged, **Tag more films by genre** on the Browse screen asks the provider about each one, about one a second, in the background. It can be stopped and resumed, and the library re-sorts itself when it ends.

Your arrangement (pinned and hidden collections, My list, viewing history, learned genres) is stored by title and name, so it survives a change of provider.

To sort things out on a computer, the phone page (TV → Live TV playlists → Export) downloads the channels, movies and series as spreadsheet files: channels with their group, playlist and guide id; titles with region, genres, category, year and rating.

**While a film or episode plays**

| Remote | What it does |
|---|---|
| **OK** | Pause or play, and bring up the controls |
| **Left / Right** | Skip back or forward; hold to skip further |
| **Up / Down** | Bring up the controls |
| **Back** | Put the controls away, then return to the library (your place is remembered) |

The controls: Start over, Previous and Next episode, Play/Pause, **Audio**, **Subtitles** (the ones built into the file), **Speed** (0.5× to 2×) and **Quality**. Move up to the timeline and use Left/Right to skip along it. Films from IPTV libraries are single files, so Quality usually has nothing to switch; the same title in the 4K category is the higher-quality version.

### Changing provider without losing your arrangement

Playlists → pick the playlist → **Replace the source** (with a link or an Xtream login), or on the phone page choose "Replace the source of…" before adding. The playlist keeps its place, and your favourites, categories, renames and hidden channels are matched to the new provider's channels: by guide id first, then by name. Hidden and renamed groups carry over when the new provider uses the same group names. If the new source can't be loaded, the playlist is left exactly as it was.

Removing a playlist and adding a new one does **not** carry anything over, so use Replace.

If a channel's link is dead, Live TV tries the channel's other links by itself (another quality, or the same channel in another playlist) and shows **Backup link** in the banner. A channel that fails twice in a row is dimmed and marked **Not working**, and Up/Down skips it; it clears as soon as it plays again.

### TV guide

Open it with the **Guide** button, the **TV guide** row at the bottom of the channel list, or the TV guide tile on the Live TV tab. Channels run down the side and time runs across; Left and Right step through programmes, CH+ and CH− page through channels, and OK tunes in. The banner and the channel list also show what's on now and next.

Where the guide comes from:

- An **Xtream login** brings its own guide.
- An **M3U playlist** is checked for a guide link in its first line (`url-tvg`).
- Otherwise set one yourself: Playlists → pick the playlist → **Set a guide link** (an XMLTV link, `.xml` or `.xml.gz`), or use the phone page.
- The free India and USA lists don't come with a guide. Their channels borrow programme data from your other playlists' guides when the channel names match.

Guides refresh every 12 hours. Only programmes for channels you actually have are kept.

### Catch-up

Channels whose provider keeps recordings show a small clock icon in the channel list. In the guide, press OK on a programme that has already aired to watch it, or **hold OK** on the programme that's on now to start it from the beginning. While a recording plays: Left and Right skip a minute (hold for five), OK pauses, Back returns to live TV.

Catch-up only exists where the provider offers it, so the free lists don't have it.

### Auto frame rate

On by default (Playlists → Picture). Live TV measures each channel's frame rate and asks the TV for a matching refresh rate: 50 Hz for 25 fps Indian channels, 60 Hz for 30 fps American ones. The screen may blank for a moment when it switches. If the TV only offers one refresh rate, nothing changes.

To show favourite channels on Home, add the **Live TV** section in Settings → Home layout.

Logins are stored only on the TV, in the app's private storage. They are not included in the settings backup.

## Making it run smoothly on the TV

A freshly installed app is *interpreted* by Android until the TV gets round to compiling it, which normally happens overnight while it's idle. Until then scrolling stutters, and every update starts that wait again. The app ships a compilation profile so the overnight pass does a thorough job, but you can also do it on the spot. With USB debugging on, from a computer (or a phone ADB app) on the same network:

```
adb connect <TV address>:5555
adb shell cmd package compile -m speed -f com.akoya.home
```

It takes a minute or two and lasts until the next update, so run it again after installing a new build. This one step makes more difference on a modest TV than anything else.

Other things that keep it light:

- Hide the channel groups you don't watch (Live TV → Manage channels). The guide is only downloaded for channels that are shown.
- Playlist and guide downloads wait 20 seconds after start-up and run at low priority, so they don't compete with the picture.

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
  livetv/PlaylistsScreen.kt  add, refresh, switch off and remove playlists; guide links; auto frame rate
  livetv/EpgStore.kt         TV guide database; Xmltv.kt reads the XMLTV files
  livetv/GuideScreen.kt      the guide grid and the now/next lookups
  livetv/Catchup.kt          builds catch-up links (Xtream, shift, append, default, Flussonic)
  livetv/FrameRate.kt        frame-rate measuring and display-rate choice
  livetv/ManageScreen.kt     channel and group options, categories, picture quality, health check
  livetv/SourceMatch.kt      matches arranged channels to a new provider's list
  vod/VodStore.kt            film and series library (Xtream API), home rows, My list, continue watching
  vod/VodSort.kt             category filing, year reading and recommendations (unit-tested)
  vod/VodActivity.kt         library navigation and the film player; VodScreens.kt is the UI
  livetv/LiveTvLauncher.kt   the Live TV tab and the Home section
```
