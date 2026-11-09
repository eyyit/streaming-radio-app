# Current README gallery provenance - 9 November 2026

The six README images under screenshots/api33-v11/ are raw adb screencaps from the
API 33 AVD named Eyyit, using the driver 10 configuration. They show the published
v11 / 0.3.0-rc9 debug APK built from committed source. The app-owned debug
ImpostorPlayerActivity was disabled for this read-only capture session so that
com.android.car.media displayed its native Android Automotive media template.

The player and stations screens are rendered by com.android.car.media, not by the
app. They show the standard AAOS host layout, not a Volvo-specific layout. The player
uses the fixture station logo; it does not show demo album artwork. Six fictional
stations, fixture logos, a local silent MP3, and fictional ICY metadata (Paper
Satellites / Midnight Colors) were used. The isolated app UID could reach only the
local emulator host; no external providers or broadcasters were contacted.

manage-playlists.png, manage-stations.png, settings.png, and station-image.png are
app-owned screens from the same v11 build and isolated fixture. The AVD was set to
1024 logical pixels wide at density 160 for capture because the default 768 x 1024
window caused a host HVAC overlay to overlap the bottom controls. The images are
unmodified 1024 x 1365 raw captures; no cropping or compositing was done. The
capture session preserved original app data via read-only access and is closed.

## Historical app-content-only captures - 3 November 2026

Actual app-content view renders from the API33 Android13 emulator,768x852 pixels. Generic emulator system bars and bottom dock are excluded. These are not Volvo UI captures or composites.

- images/player.png: playing fictional Afterglow FM with Paper Satellites / Midnight Colors custom demo album art. Media3 PLAYING confirmed during capture.
- images/reorder.png: All stations, per-station original icons and up/down controls; first up arrow correctly disabled.
- images/favorites.png: ordered Favorites with the same reorder controls.
- images/settings.png: all8 Settings captions22sp regular/400 and all cells56dp high; switches48dp tall. Updated after typography correction; render assertions passed without replacing the library.
- images/theme.png: theme selection with blue selected.

Six stations, artist, album, audio and artwork are controlled original demo fixtures. Not included in the public app catalog. Online artwork is enabled for illustrations and defaults on in release unless a saved preference disables it. See tools/screenshot-fixtures for original assets and capture instructions. No commercial recordings or broadcaster artwork were added to this gallery.

The user's real debug library/settings were restored from a private backup after capture:128files verified by content hashes and25demo-only files removed. That backup is not in this repo. Final Back-navigation maintenance changes do not alter captured layouts.
