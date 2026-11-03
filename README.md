# Streaming Radio

Native Android Automotive OS internet radio player by Impostor Labs. Supports hosted M3U playlists, ordered Favorites, station and track metadata, optional artwork, and customizable themes.

A modified GPLv3-or-later fork of [Twinsen Radio](https://github.com/szczepl/twinsen-radio), originally copyright 2026 Leszek Szczepanowski. Original authors retain their copyrights. Not affiliated with Google, Volvo, or broadcasters.

## Current status

Internal-test candidate 0.3.0-rc1, version code 3. Package `com.impostorlabs.streamingradio`; min SDK 28, target SDK 36. No stations are bundled. Add your own hosted M3U. Online artwork defaults off; Apple/iTunes artwork lookup is disabled in release builds. API33 matches the development Volvo's Android 13 version, but a generic emulator does not reproduce Volvo hardware or UI.

App-owned management/player screens are parked-only. Driving playback uses the native AAOS media host. Vehicle audio, focus and driving-transition acceptance remain to be completed. This is not a claim of Play approval or universal content licensing.

## Build and license

See [BUILD.md](BUILD.md), [LICENSE](LICENSE), [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md), and [upstream README](UPSTREAM-README.md). Build with JDK17 and Android SDK36. No private signing key is required for an unsigned build.

## Privacy and publishing

GitHub Pages privacy page is `docs/privacy.html` (publish main /docs). Privacy contact: impostor.labs@gmail.com. See [release handoff](docs/HANDOFF-NEXT.md) for exact artifact evidence, remaining publishing steps and known limits. Broadcaster streams and artwork have separate rights from this software license.
