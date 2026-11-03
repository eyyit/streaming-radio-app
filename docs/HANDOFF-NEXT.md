# Current handoff — 2 November 2026

## Scope and location
Server checkout: server.eyyit.com:/opt/repos/streaming-radio-app.
GitHub: https://github.com/eyyit/streaming-radio-app (verified actual fork of szczepl/twinsen-radio).
User explicitly approved the initial app-change commit and push on 2 November 2026. Do not upload to Play or operate the physical Volvo. Initial source push is authorized; Play uploads and physical car actions remain user operated. No secrets, upload keystore, app data or private playlist backups belong in this repo.

## Artifact / mapping
User generated their own upload key, built successfully and reports uploading version3 to AAOS internal testing. Local outputs/Streaming-Radio-play-upload.aab SHA256: 42C6836C1079053E8AFD78BD9D2647F83452EF2E9D2ABFDAF375929EA019E59A. The Console copy was not downloaded for independent hash comparison.
Release minification is false; no R8 mapping.txt is needed. The signed AAB has no native .so libraries or mapping metadata, includes GPL/dependency/privacy assets, and its bundled station asset is []. Signature verification returns jar verified; the self-signed upload certificate is expected. Generated release config: package com.impostorlabs.streamingradio, versionCode3, target36, min28, empty starter, Apple artwork disabled. No source files were newer than the local signed AAB at inspection. Source export uses an allowlist and hashes, not an independently reproduced binary.

## Internal test / compliance
- No bundled station requires redistribution clearance in this candidate. Do not infer permission to bundle stations/logos from a user's private playlist.
- Privacy page is prepared at docs/privacy.html. Enable GitHub Pages from main /docs; expected policy URL is https://eyyit.github.io/streaming-radio-app/privacy.html. Verify it actually loads before entering it in Play Console. A folder on disk is not a published URL. The app already contains full local privacy text.
- GPL notices and complete license are included. Publish this source revision/download and link it beside the binary distribution; no private signing keys required. Source availability does not require publishing keys. Do not link old source ZIPs as matching version3.
- Internal-only testing is exempt from the Data safety form, not general Play policies. Complete it before moving to closed/open/production tracks. Do not assert all traffic is encrypted: user radio streams may use HTTP.
- BIS guidance excludes encryption supporting entertainment/dedicated music players and digital signatures from 5A002.a. Code review found platform HTTPS and SHA256 identifier hashing, no general-purpose encryption feature. This supports an ordinary entertainment-software self-classification; no formal BIS determination or sanctions screening is claimed.
- Artwork defaults off. Apple requests disabled in release. Radio Browser / MusicBrainz / Cover Art Archive access is not blanket copyright permission for every image. For the first private vehicle test keep online artwork off; public artwork rights/provider review remains open. CAA explicitly retains image-owner copyrights.
- Audio emulator defects and vehicle acceptance remain open. Current prior JVM suite242 passed; not rerun in this read-only release audit. Earlier OSV scan80 components on2Nov had no matches, not a security guarantee. External-UID runtime probe remains unverified.

## First vehicle test (user operated, parked)
Opt in using the Google account in the Volvo, open direct Play listing, install from web device selector. Verify startup, own playlist import, play/pause/next/previous, favorites, sound level, audio focus/ducking/navigation and reconnect. Do not claim motion restrictions accepted until a controlled safe test establishes custom screens close and native media host remains available. Avoid live road debugging.

## Design / continuation
Preserve approved charcoal UI geometry and positions; read docs/DESIGN-SYSTEM.md. Update this handoff after any change. Structural redesign requires approved mocks. No automated releases or unattended dependency merges. Keep public maintenance/security updates human reviewed.


## Initial commit outcome
Initial app-change commit created locally on main with upstream history preserved. Dependency-check tool regression tests:31 passed,0 failed. Initial elliott-login push failed SSH authentication. Root login authenticates as GitHub eyyit; retry uses root with the same global guard explicitly enabled. User approved exactly one global gitleaks.ignore fingerprint: upstream 083081bfcce0484f595a59f9e50e201368de804b:app/src/main/java/net/mspanc/twinsenradio/playback/RadioService.kt:generic-api-key:61, a Kotlin preference-constant false positive. Existing allowlist backed up; no scanner/rule/hook bypass. Verify actual remote SHA before claiming publication. No Play upload, car action or key transfer performed.
