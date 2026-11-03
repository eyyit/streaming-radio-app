# External Dependencies

Twinsen Radio has no paid or closed-source dependencies. Everything below
is free and open source, and none of these libraries require an API key
or an account.

| Library | Version | License | Role in the project |
|---|---|---|---|
| [AndroidX Media3](https://github.com/androidx/media) (exoplayer, exoplayer-hls, session, datasource) | 1.11.0 | Apache-2.0 | Stream playback, Android Auto session |
| [AndroidX Core KTX](https://developer.android.com/jetpack/androidx/releases/core) | 1.17.0 | Apache-2.0 | Kotlin extensions for the platform |
| [AndroidX AppCompat](https://developer.android.com/jetpack/androidx/releases/appcompat) | 1.7.1 | Apache-2.0 | UI backward compatibility |
| [Material Components for Android](https://github.com/material-components/material-components-android) | 1.13.0 | Apache-2.0 | Material 3 components |
| [AndroidX RecyclerView](https://developer.android.com/jetpack/androidx/releases/recyclerview) | 1.4.0 | Apache-2.0 | Station list |
| [AndroidX ConstraintLayout](https://developer.android.com/jetpack/androidx/releases/constraintlayout) | 2.2.1 | Apache-2.0 | Screen layouts |
| [AndroidX Media](https://developer.android.com/jetpack/androidx/releases/media) | 1.7.0 | Apache-2.0 | Compatibility with `MediaBrowserServiceCompat` |
| [Kotlin Coroutines (Android)](https://github.com/Kotlin/kotlinx.coroutines) | 1.10.2 | Apache-2.0 | Concurrency, `StateFlow` |
| [Guava (Android)](https://github.com/google/guava) | 33.7.2-android | Apache-2.0 | `ListenableFuture` required by Media3; security update for GHSA-xxph-c9ww-hj94 |

Build tools: Android Gradle Plugin 8.13.2, Kotlin 2.2.21, Gradle 8.14.3
(both fetched automatically by the wrapper).

## Data and network services

At runtime, the app queries free, public APIs — none of which require a
key or registration:

* **[radio-browser.info](https://www.radio-browser.info/)** — a station
  directory for searches outside the built-in list (`RadioBrowser.kt`).
* **iTunes Search API**, **MusicBrainz**, and **Cover Art Archive** — track
  cover art (`CoverArtLookup.kt`).

See [Legal disclaimers in README.md](README.md#legal-disclaimers) regarding
station logos and the audio streams themselves.

## Streaming Radio modifications (2 November 2026)

The rounded Settings and refresh artwork glyphs derive from Lucide Icons,
ISC license, copyright Lucide Icons and Contributors. Full GPLv3, Apache-2.0
and Lucide ISC texts are bundled under app/src/main/assets/legal.
The public release candidate disables the Apple/iTunes integration pending
its promotional-content requirements. Radio Browser's directory-data license
does not license the linked audio, branding or artwork. See the release privacy
and license-compliance documents for external service disclosures.
