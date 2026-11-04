# Build the source candidate

This is a reproducible-source starting point, not a signed or distribution-ready release. The checked-in Gradle wrapper declares Gradle 8.14.3. The project uses Android Gradle Plugin 8.13.2, Kotlin 2.2.21, JDK 17, and Android SDK platform 36. The wrapper downloads Gradle and dependencies, so building requires network access.

From the repository root, build the automotive release variant with:

```sh
./gradlew :app:assembleAutomotiveRelease
```

On Windows, use `gradlew.bat :app:assembleAutomotiveRelease`. Unit tests can be run with `./gradlew :app:testAutomotiveReleaseUnitTest`. On2November2026 the debug and release unit suites each passed187tests; release lint reported0errors and126warnings. These are functional/static checks, not a security certification or physical-vehicle acceptance.

No signing key is required to inspect the source or produce an unsigned local candidate. Do not put signing keys, passwords, or local SDK paths in the repository. Public signing and distribution are not configured or authorized by this source export.

For a bundle, use `./gradlew :app:bundleAutomotiveRelease`. Public identity can
be staged with `-PpublicApplicationId=com.impostorlabs.streamingradio`. After
station/hosting rights are approved, `-PpublicStarterPlaylistUrl=https://…`
configures a separately removable starter source. Debug prototype seeding uses
`-PlocalDefaultPlaylistUrl=https://…`; no developer's personal playlist is a
default in this exported source. The debug Automotive application ID stays
separate from the public candidate so existing local data is preserved.

Optional upload signing reads all four environment variables:
`STREAMING_RADIO_KEYSTORE`, `STREAMING_RADIO_STORE_PASSWORD`,
`STREAMING_RADIO_KEY_ALIAS`, `STREAMING_RADIO_KEY_PASSWORD`. Leave all unset for
unsigned inspection. Do not put their values into shell history, source,
build logs or the source archive. No production signing key is included.

Shared verification (`node tools/verify-automotive.cjs`) additionally requires Node.js 24 and PowerShell 7 or newer (`pwsh` on PATH). Its developer-script tests use isolated fake ADB/Gradle processes; they do not contact a device.

## Emulator-only install helper

On Windows, `tools/install.ps1 -Automotive -Serial emulator-5580` uses the developer toolchain paths declared in the script. It refuses physical devices and requires the selected emulator to have Driver user10 current. Installation and launch explicitly target that serial and user10.

Optional `tools/emulator-host-layout` helpers require an explicit `-AdbPath`. Wide-controls also requires an external `-OverlayApkPath` when installing its local resource overlay; that APK is not bundled here. These helpers are not required to build the app and do not represent the Volvo native player.

## Exported source bundle

Run `node tools/export-public-source.cjs` from the project root to create `dist/public-source`, or pass `--output PATH` for an explicit destination. The exporter uses an explicit allowlist, rejects symlinks in included trees, scans text for high-confidence secret patterns, and writes SHA-256 entries to `SOURCE-MANIFEST.json`. It refuses to overwrite a nonempty destination. Reserved-domain credentials in security rejection tests are reported as reviewed fixtures. The scan is a safeguard, not a comprehensive source or security audit.
