# Streaming Radio development handoff

## Current candidate

Version 0.3.0-rc7, code 9. Package: `com.impostorlabs.streamingradio`.
Release scope: Android Automotive OS internal testing; not production.

## Approved UI contract

Read `docs/DESIGN-SYSTEM.md` and `AGENTS.md` before editing UI.
Native Settings and playlist management share typography, row sizing and themed
Done controls. The playlist overview shows the actual source URL on the plain
canvas, using a middle ellipsis only when width requires it. Station count and
equal-width neutral Open/Remove buttons follow. Local saved sources retain names.
The P8 station rows and their large right-side actions keep their approved sizes.
Do not redesign adjacent screens during a targeted fix.

## Verification and release

Use the full Docker verification described in `BUILD.md`; GitHub Actions must
pass on the exact release commit before publishing to the AAOS internal track.
Check installed rendering on Android 13/API 33 without clearing saved data.
Debug prototype screenshots do not describe the OEM-controlled retail player.
Use fictional documentation fixtures; do not bundle personal station lists.
Keep signing material and publisher credentials outside Git and Docker images.
Every changed Play artifact requires a higher unused version code.

## Known boundaries

The vehicle controls native media layout, action placement and text truncation.
Artwork depends on metadata, matching catalogs and available licensed sources.
System-wide vehicle audio distortion remains unresolved; no fix is claimed.
Emulator checks do not establish physical-vehicle acceptance or Play approval.
