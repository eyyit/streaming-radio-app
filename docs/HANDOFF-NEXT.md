# Current release candidate: version 7 / 0.3.0-rc5

Native Settings now fills the available app area, with readable 26sp labels,
equal minimum 80dp rows, scrolling and lifecycle cleanup. Retail release builds
exclude the prototype player; playback and browsing use the vehicle media host.
Artwork placement and control placement remain controlled by that host. Native
shuffle is not implemented. System-wide startup audio distortion is unresolved.

Linux Docker builds are documented in BUILD.md and docs/DOCKER-BUILD.md.
Run tools/docker-build.sh verify for the shared local/CI checks, or signed-bundle
for a signed candidate after private signing setup. Build helpers do not publish.
Signing and Play credentials must remain outside the repository and image.

Before this publication, all 68 tool tests, four JVM test variants, release lint,
source inventory and dependency checks passed. Ten fresh Settings reviewers
reported zero actionable issues. GitHub Actions must be checked on the uploaded
commit; a local pass is not a hosted CI result. Internal testing is separate from
production acceptance. Older notes below are historical where they conflict.

# Candidate v6 publication preparation - 3 November 2026

Current app sources match the 0.3.0-rc4/versionCode6 candidate. Online artwork now defaults on when no choice is saved; existing explicit opt-outs and hidden-track-art choices remain authoritative. All four JVM variants pass279 tests; release lint has0errors (134 automotive/110 phonewarnings). API33 native favorite action advertises a heart; artwork catalog selection/retry/caching and approved playlist/image-editor fixes are included. The custom parked UI does not replace the OEM native media host. Real-car system-wide audio distortion remains unresolved; public-release acceptance is blocked. No speculative audio-pipeline fix was made.

User authorized a GitHub source push; the user handles Play uploads separately. Existing screenshots and approved image-editor mock are retained, including the replacement Settings image. No signing key, private playlist, car photograph, internal Google/Volvo report or raw vehicle log is included. Older notes below are historical; in particular mock-only/editor-pending and v3/v4 artifact notes do not describe this source candidate. CI result must be checked for the uploaded SHA before calling it passed.

# Latest verified status — 3 November 2026

App code commit58364c536cadd03c6c0f86f39d571bbc421e6089 passed GitHub Actions run41644510296: https://github.com/eyyit/streaming-radio-app/actions/runs/41644510296 . Prepared source inventory, dependency-tool tests, SDK setup, both automotive JVM test variants, full release lint, unsigned bundle, runtime inventory, OSV scan and artifact retention passed. The CI issue was3stale upstream files left after an export overlay; those were reconciled and guarded. Done buttons now use the theme accent/onAccent, with emulator render checks passing. This handoff update changes documentation/inventory hashes only; application code is unchanged. No Play upload or physical-car action has occurred. Version4 is still unsigned locally; user signs with their own key before any future Play upload.

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

## README and build-worker update
README refreshed with actual Settings/Theme emulator captures in docs/images; historical capture status and release defaults are labeled. No app runtime code changed and no versionCode increment: uploaded AAB3 remains the internal-test candidate. Windows local outputs Build-Play-bundle.ps1, Rebuild-AAOS-emulator.ps1 and Build-Release-Candidate.ps1 now use Environment.ProcessorCount (24 on current Windows machine) and --parallel; syntax checks passed. Gradle default already uses available processors when not overridden. No claim of 100% CPU use or measured speedup. Privacy Pages URL returned404 on2Nov: enable main /docs Pages and verify before using it in Console. Changes currently uncommitted/unpushed; initial-push approval was for earlier work.

## 3 November — equal Settings rows / screenshot refresh / CI
- Only production UI change: RadioManagement Settings toggle rows fixed56dp to match action cells, centered48dp switches. Live accessibility bounds confirm56dp rows. VersionCode4 /0.3.0-rc2 prepared; user's uploaded AAB remains3. New version4 AAB is an unsigned local build; rerun user signing helper to produce a new upload artifact. Do not accidentally upload old outputs/Streaming-Radio-play-upload.aab as version4.
- Debug screenshot harness mode screenshots uses six fictional M3U stations, original768px station icons and original album art, controlled local synthetic WAV/ICY audio, injected test catalog lookup and muted playback. Test receiver captures actual app content; native emulator bars/dock are excluded. Not a Volvo capture, not a public default catalog. This code is androidTest only, absent from release app.
- API33/5580/qemu1/user10 verified before install/mutations. Debug build/tests and unsigned release bundle passed; installed locally. Screenshot harness passed with actual Media3 playing state, ICY title and local album-art URI. User's original app data was backed up privately, then restored:128 original files matched byte hashes;25 demo-only files removed. Original app relaunched. Never publish the private backup.
- README now shows player/custom album art, All stations/up-down arrows, Settings equal cells and Theme; Favorites capture also linked. Screenshots are original fictional data, with explicit emulator/content-only disclaimer. Files in docs/images.
- User-supplied Actions run41578673681 failed before Gradle: bare sdkmanager step exit127. Workflow prepared with ubuntu24.04 and pinned android-actions/setup-android v4.0.4 before installing SDK36. Needs commit/push/new CI run; no green result claimed. Node20 deprecation annotations on older actions remain warnings, not this failure.
- Runtime row change requires a newly signed version4 AAB; README, screenshot, CI and worker-count changes alone would not. No Play upload or physical-car action performed. Changes currently uncommitted/unpushed pending user approval.

The full release lint run initially found GestureBackNavigation in the old onBackPressed override. Activity now uses a lifecycle-aware AndroidX OnBackPressedCallback to close management first, then delegate to the dispatcher fallback; no suppression or design change. Final verification results follow after build completes. Original screenshot helper/art source is in tools/screenshot-fixtures; private restore/backup helpers remain outside this public repo.

Final verification (3 November): debug build/242 JVM tests passed; unsigned automotive release bundle built; release JVM242 tests passed; full release lint0errors/135warnings after Back-dispatcher migration. Final debug APK installed on verifiedAPI33/user10, original imported library retained. Live Back check passed SettingsOpened=true, BackClosedSettings=true, PlayerRemainedVisible=true. New unsigned version4 AAB has0RSA signature entries; user must rerun local Build-Play-bundle.ps1 with their upload key/password to sign it. Existing signed upload copy remains version3 until that command succeeds. No new screenshot, UI or CI changes have been committed/pushed yet.

## Standing consistency requirements — 3 November
User explicitly required project-wide preservation/consistency rules, not repeated per-view corrections. AGENTS.md, alwaysApply .cursor/rules/streaming-radio-design.mdc and DESIGN-SYSTEM.md now require narrow property changes, preserved typography/controls/layout, shared tokens, equivalent row styling, before/after render review and honest build/install/CI status. Source exporter now includes AGENTS.md.
Settings captions now all22sp sans-serif regular/400 with shared constants; rows56dp; switches48dp height. Done/Back/Cancel retain18sp medium. Settings-only action label overrides avoid changing unrelated management views. New androidTest mode settings-typography checks all8captions, font size, font weight and row heights and saves actual app-content PNG without replacing the library.
Latest debug + test APK + unsigned release bundle build passed2m1s. Installed on verified API33 emulator/user10; all4render assertions passed. Corrected screenshot replaces docs/images/settings.png. Library was not replaced in this verification. No new commit/push or Play/car action. Actions run41578673681 still refers to older1342ea9 and SDK-install exit127; prepared workflow fix awaits publication/new run.

## Publication authorized — 3 November
User explicitly approved committing and pushing the prepared UI, README, design rules and Actions fixes and checking the resulting CI run until it passes. No Play upload, physical-car action, broadcaster outreach or credential transfer is authorized. Prior notes saying these changes await commit/push are historical. CI result must be checked against the new exact commit; no green claim yet.

## Resumed: CI source reconciliation and Done accents — 3 November
User resumed and supplied the failed Gradle block. Run41585517104 failed compiling R357NowPlayingTest because its R357NowPlaying implementation was removed. Prepared export overlay had retained3upstream files absent from the177-file app/src inventory: R357NowPlayingTest, TritonNowPlaying and TritonNowPlayingTest. Removed exactly those3files to match the tested prepared source. Active feature tests are retained; no test task was skipped. Early check-source-tree guard reproduced the mismatch before cleanup and passed after it. Gradlew now executable, and exporter preserves0755.
Shared Done controls now use current theme accent/onAccent like Theme; Back/Cancel unchanged. Local debug/test/unsigned release builds,242release tests and full lint passed. Emulator settings-typography mode passed6checks:8captions,22sp,400weight,56dp rows and Done accent/text. Settings README capture refreshed; user library not replaced. Version4 remains the next unsigned candidate, existing Play upload remains3. No Play or physical car actions.
These fixes are authorized for commit/push and another Actions run; priorCIstatus is failed until the new exact SHA is observed green.

Final Done theme-return correction: Settings reuses its view after closing Theme, so it previously retained the old Done color. Closing Theme now refreshes Done styling on that Settings view. Emulator settings-typography mode verifies a different-theme roundtrip, then restores the original selection. All7render checks passed, including preserved22sp/400weight/56dp geometry, initial accent/onAccent and theme-return refresh. Debug and test APK build passed25s and were installed on the verified emulator; original library/theme retained. Both prior full GitHub runs41644510296 and41645342073 are green. This narrow followup requires its own CI run before claiming latestmain green. No Play/car actions.

## Stronger local checks — emulator explicitly excluded
User asked why GitHub caught issues local tests missed, then explicitly cancelled emulator relaunch. No emulator/device actions are authorized in this task. Gap: the local prepared tree differed from the overlaid fork, and CI assumed sdkmanager availability. Added8source parity regressions and8verification-driver regressions (47tool tests total with existing31). Source guard now checks expected inventory vs Git index vs actual files, including deleted/untracked sources, invalid manifests and symlink boundaries. Shared verify-automotive driver performs environment preflight and the same full Gradle/OSV stages locally and in CI; unsigned-only child environment; JSONreceipt records commit/dirty state and stage results. preflight-only success is distinctly labeled. CI SDKinstall aligns toAGP8.13 defaultbuild-tools35.0.0 withplatform36.
Windows outputs/Verify-AAOS-local.ps1/.cmd creates a fresh temporary checkout of an exact server/GitHub revision and uses the existing isolated SDK/JDK. No app installation, emulator interaction, push, signing key access or Play action in that helper. Candidate tooling must be tested from a fresh exact commit before pushing, then GitHubchecked. See docs/VERIFICATION.md.

First clean-checkout local prepush run caught Windows-specific SDK test-fixture assumptions before push. Native aapt2.exe/sdkmanager.bat and platform checks now used on Windows; Linux executable checks preserved. Out-of-root boundary test uses a Windows directory junction so it can run without symlink privilege. Candidate remains unpublished until fresh full verification passes.

Pending local UI changes, not committed/pushed: user requested global themed Done for read-only management, domain+filename playlist labels, remove Settings Station images and rework image search. Four read-only headers nowDone; forms Cancel/Save preserved. Name helper normalizes legacy host-only/blank defaults, preserves custom/local labels, strips URL query/fragment/userinfo, keeps decoded+, sanitizes controls and bounds long names with filename retained.7new JVM tests (249release total) pass. Existing runningAPI33 emulator was verified and updated without restarting the VM;10render assertions pass including Manage playlists Done colors, Settings7rows/noStationimages and preserved22sp/400/56dp. User library retained. New image picker is MOCK-ONLY pending approval: originalworkspace outputs/mocks/station-editor-proposal/IS01-ready.png,IS02-keyboard-search.png,IS03-no-results.png. Proposed fixed top-right Save image survives scrolling/keyboard, Cancel returns without saving, target station distinct from query; original icons/palette/dock retained. StationImageDialog.kt was not modified. Do not implement the new picker until user approves these mocks.

Approved IS01-IS03 editor implemented source-only in StationImageDialog, RadioManagement and ImpostorPlayerActivity. Fixed header/search and Save image; body scrolls independently. No compilation, tests, installation or restart per user hold. Application edits remain unpublished.
