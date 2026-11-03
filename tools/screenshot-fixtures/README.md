# Original emulator screenshot fixtures

These fictional stations,768px station icons, album illustration, artist/title and quiet synthetic audio are project-created demo content. They are not bundled with the release app. The project GPLv3-or-later license applies.

Run `node tools/screenshot-fixtures/demo-server.cjs` on the emulator host; it serves fixtures on port18766. Regenerate illustrations on Windows with Make-Demo-Art.ps1 if needed. No commercial recordings, broadcaster marks, external catalog scraping or private playlist are used.

Build/install the automotive debug and androidTest APKs, then run the existing test instrumentation with `-e mode screenshots`. It intentionally replaces debug app state. FIRST back up app-private shared_prefs, files and cache and verify that backup. NEVER run this mode against a release package or a physical car. The capture helper is androidTest-only, not part of the release APK.

While the harness waits (maximum10min), navigate normally and broadcast `com.impostorlabs.streamingradio.debug.CAPTURE_SCREENSHOT` with a simple `name` extra. Captures are app-private files/screenshots/name.png. It draws the actual app content view, omitting native emulator system bars/dock; the images are not Volvo screenshots. Broadcast `com.impostorlabs.streamingradio.debug.FINISH_SCREENSHOTS` when done.

Pull the PNGs, stop the app, restore the original saved state, remove demo-only files, verify saved-data hashes, and stop the fixture server. Do not publish private backups, data dumps or emulator logs.
