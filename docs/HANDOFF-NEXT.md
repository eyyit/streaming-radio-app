# Shared updates and coordinated releases

Follow the [shared-update and release contract](WORKSTREAMS.md#shared-update-and-release-contract).
For a paired release, use one source revision and the same user-visible
version name on both bundles, with distinct Play version codes. Phone code
19 used 0.19.0 for internal and closed testing. Existing staged and published
version labels remain historical. The local paired candidate is 0.21.0 for
both builds, provisionally phone code 21 and AAOS code 22. Check both codes
against the full Play inventory before a later submission. The staged
AAOS v20 and released phone v19 artifacts retain their embedded names.

# Phone player gesture guide

The full-screen player shows the approved guide once a station is available.
Got it persists dismissal locally. The cover arrows explain horizontal station
swipes and vertical library swipes.

# Phone version 17 candidate

L3-C puts the station library on the left and the player on the right in
landscape. Full artwork drags finish visually before release. The phone uses
the waveform app icon, with matching Play listing artwork. Keep the exact A
shuffle asset and use the phone internal track for this candidate.


# Native AAOS version 14 recovery

The Volvo showed the phone activity. Phone and native AAOS releases share
the package ID. Native v14 outranks phone v13 and is intended for the
automotive internal track as an in-place update. Verify the installed
artifact and native browse and playback while the host is in driving mode.
The media session opens the system media host. Playlist editing is parked.

# Phone version 13 corrections - private candidate

Phone version 13 / 0.3.0-rc11 targets the existing Streaming Radio package;
automotive remains version 11 / 0.3.0-rc9. The approved shuffle A PNG is locked
at SHA256 b431a22813b224e68bc07b5362f79137fa7c063319a0c406a1cfc192e8a8d032
and must not be redrawn.

The private source includes the native Cast volume foundation, general media-key
bridge and receiver-backed legacy volume dialog. The Cast button opens the app's
receiver-backed dialog because the Android System Output Switcher showed a
seekbar change from 32% to 18% while the receiver stayed at 32% after 45 seconds.
That observation came from the selected controls candidate APK
SHA256 a8e63c22215846163006ac1d61aacfb42f2f7bc8074c50d20d409116c20ac5c6.
On that candidate, global media controls, volume key changes from 32% to 34%
and back, and owned-session cold resume passed. No acoustic confirmation is
claimed. The final integrated artifact still needs device verification of the
receiver-backed dialog; the earlier candidate checks do not certify it.

Native Cast volume uses Android's public framework APIs. On Android 8.0, the
phone session uses the app's key handler so only the initial headset play/pause
press changes playback. The final native controls still need device verification.

The approved artwork, import-preview, layout, naming and loading-ring changes
have private build receipts. Required release gates include the exact-source
four-variant matrix, screenshots from the final artifact, fresh local review,
required CI and manual publication approval. Leszek's Apache 2.0 grant covers
his upstream Twinsen Radio code. Elliott authorized Apache 2.0 for the fork's
original contributions to the extent he holds rights in them. Verify Cast SDK
distribution terms separately. Preserve the NOTICE and the Account & About
credit in both apps. Earlier version 12 publication authorization is historical.

No commit, push, signing, Play upload or canonical server write is authorized by
this handoff. Private evidence lives under corrections-20261110. Notes below are
historical and do not report the current candidate's readiness.

The web and bundled privacy policies now share the same text, covering optional
Firebase playlist transfer and phone Cast. Verify the public policy URL before
submitting to Play. The phone Cast notification now shows the receiver,
opens the expanded player when tapped, and offers Disconnect to request
that the sender session end while the receiver keeps playing. Android 13 media
controls receive the same action through the platform media session.
The separate Stop media command still stops receiver playback.

Pixel 4a hardware check (11 November): Android 13 showed Kids Speaker on
the media card, and tapping the card opened the expanded phone player.
Disconnect removed the phone Cast session, but Kids Speaker also lost its
receiver app and media session on both the piano HLS and Groove Salad MP3
tests. The app calls endCurrentSession(false), the same call used by the
SDK's Disconnect action. Continued playback after disconnect is unverified
and this hardware check failed. The built-in Cast dialog on Kids Speaker
offered only Stop casting. A separate build then used AndroidX MediaRouter
UNSELECT_REASON_DISCONNECTED. The Pixel logged reason 1, but the receiver
app and session again disappeared while Groove Salad was playing. The
experimental change was reverted. AndroidX documents keep-playing as a
route capability that is normally available with multiple sources. No
second sender was connected for these tests. Do not treat a passing unit
test or either Android disconnect call as proof of continued playback.

Two-sender retest (11 November): installed the same debug APK on a second
Pixel 4a and joined the first Pixel's existing Groove Salad Cast session
without starting playback on the second phone. The receiver and media
session IDs matched before join, after join, and after the first Pixel
tapped Disconnect. The first Pixel disconnected, the second stayed
connected, and Kids Speaker reported PLAYING as playback advanced.
The single-sender failure above still applies.

The notification now uses the route's advertised capability to choose
Disconnect or Stop casting. Neither notification action force-stops the
receiver. On Kids Speaker the provider displayed Stop casting even with both
Pixels joined. The first Pixel's Stop casting action removed its controls
while the second stayed joined and receiver playback advanced.

A separate rejoin defect remains: when the first Pixel joined while it had
a saved local selection, the receiver app stayed open but Groove Salad
reloaded with a new media session and paused. Joining an already-playing
session must preserve that receiver playback before this candidate ships.

---

# Phone version 12 source release prepared - 9 November 2026

Native phone library, player, collection navigation and URL/local M3U import
flows are approved and implemented. Phone release targets the existing package
com.impostorlabs.streamingradio, version12 / 0.3.0-rc10; its debug package is
com.impostorlabs.streamingradio.phone.debug. The approved A image and indicator
are reused unchanged. No starter stations are bundled; phone transfer remains
disabled by default and has not been deployed.

The reviewed private snapshot passed all1580 JVM tests, both release lints with
zero errors, source/tool checks and dependency scanning. Ten fresh independent
logical review roles returned no findings after repairs. Exact public-commit
verification and CI remain mandatory before a phone-only internal Play upload.
AAOS version11 remains on automotive:internal; this source preparation does not
publish or update any Play track. The owner authorized this source release and
a one-time exception to the500-line upload-size limit on9 November2026.

--- Earlier release and workstream history follows ---

# Version10 HLS candidate ready locally; publication held - 5 November 2026

Canonical /opt/repos/streaming-radio-app remains uncommitted on base381121c1859f402c09c083a46c8dd054cc7ef97a. Prepared code10 / 0.3.0-rc8 for future automotive:internal. NO commit, push, Play edit/upload, emulator launch, relay deployment/restart, idle origin activation or car action. Do not launch XC40/any emulator after the reported Windows hard lockup without fresh authorization.

Use ONLY /opt/builds/streaming-radio-app/hls-release-20261105/ready/Streaming-Radio-v10-rc8.aab
SHA256 2a6979477013b10888506ac31b1dc50adf8c658960e45c85fead9ad1ea8a2275
Matching source ZIP and READINESS.md are alongside it. candidate-state.json is current; older signed candidates e4c485c... and1922390... are STALE. Version10 is next by local evidence; availability was not queried through a mutating Play edit.

Final fullverify repaired-source-03: four variants336tests each,0 failures/errors/skips; release lint0errors (existing warnings remain), instrumentation compile/source-tree/tool tests pass, OSV80components/0matches. Actual service/state regression suite25 passed offline/networkdisabled. Real REDs reproduced cache trust reuse, HLSemptytitle, ICYexplicitartclear and absent-to-explicit-clear sequence. Final mutation disabled HLSclear, killed relevant service assertion, restored exactSHA, then5service tests passed. Proof final-mutation/proof.json.

Review:10 independent logical full-change roles, then10 fresh isolated final four-file amendment roles; one native provider, not model/provider diversity. Final deterministic triage0actionable,0requiredcomments,0discarded evidence, no doubtguard; fullbase composed floor clear. Cache namespaces bind image trust policies. HLSemptytitle clears while legacyICY/API title authority remains; explicit/invalid artclear follows documented fallback; field absence differs from firstclear and repeats are idempotent. No unrelated UI redesign.

Signed upload certificate matches version9. Public package correct, prototype removed, nativeSettings retained, bundled catalogempty, no nativeABI restriction. Minificationfalse: no R8mapping required. Signedsource manifest 092529aa535e32cfc75e6190ce73ff05325ef7e3157131cd5a779f2394fbf04c; verified manifest 6b6cb01e59cedade0030791f626a0019bab4383a7cb79ab280fd2bbc1156ef63 differs only generatedUPSTREAM-README/Gitmetadata; every buildinput hash compared identical.

PUBLICATION STILL HELD: future source changes need <=500line reviewed upload units (UPLOAD-STACK-PLAN.md is only a plan), authorized commits/push and exact-new-commit GitHubActions before Play per projectrule. Live read-only GitHub discovery found unprotectedmain/no rulesets; existing oldmainActions success does NOT cover this dirtycandidate. Publish exactGPL corresponding source with eventualdistribution. No remotecheck on newcandidate claimed.

LIMITS: relay work remains separately owned/local, liveHLS chunkqueue/audio hardware/OEM rendering acceptance unverified; sourceimage downloads retain existing exact NovaHTTPS policy. This is not a verified fix for vehicle-wide distortedchimes. URL label spacing remains separately pending. No signing credentials in source/archive/logs. Read ready/READINESS.md and review-provenance.json for exactscope.

--- Earlier notes are historical ---

# Release review repair still in progress - 5 November 2026

Cache-policy repair passed actual-fetch RED/GREEN and full329tests pervariant.
The subsequent signed bundle SHA1922390d6939a305826e1960b21f8a3b8a638c1ddcbb6f6bcf9f73b5da9a19a5
is also STALE: a finalreview found explicit HLS TIT2 empty snapshots preserve old
title/art due to legacyICY empty-ignore behavior. Surgical HLS-only clear and a
service-level regression are in progress. Do not upload any existing artifact.
No emulator/Play/Git/car/relaydeployment action authorized. Final status lives in
/opt/builds/streaming-radio-app/hls-release-20261105/candidate-state.json.

--- Earlier preparation state ---

# Version10 HLS internal-test preparation - 5 November 2026

Current authorization: finish local reviews and prepare a candidate only. NO emulator launch, commit/push, Play upload, relay deployment/restart, idle origin activation or physical-car action. Prior XC40 launch preceded a Windows hard lockup; do not restart it. Signing credentials remain outside source/images and must never be displayed.

Canonical source /opt/repos/streaming-radio-app is uncommitted on base381121c1859f402c09c083a46c8dd054cc7ef97a. Local release metadata is code10 / 0.3.0-rc8; latest locally evidenced Play release is code9/rc7. Code10 has not been reserved/queried via Play because existing track helper creates an edit; no Play mutation is authorized. Confirm availability during the eventual authorized upload.

Private evidence /opt/builds/streaming-radio-app/hls-release-20261105. Initial version10 fullverify and signing passed, but reviewer3 found policy-blind artwork cache reuse. That signed bundle (SHA256 e4c485c2861f9dd1ceadc105b91aea9df89e8bc9695f8d2f99e137d924b87dc9) is STALE and MUST NOT be uploaded. Cache-policy regression/repair, fresh final snapshot, full verification and independent final review are in progress. Consult candidate-state.json/final readiness receipt for completion; do not infer readiness from an old successful log.

Live read-only GitHub discovery: main remains base381121c1859f402c09c083a46c8dd054cc7ef97a; existing run41960704114 completed success. Branch unprotected and rulesets empty, so no server-enforced required checks discovered. Project rules still require exact new candidate commit CI before Play; current old main run is not newcandidate evidence. No GitHub upload or workflow dispatch performed. Full dirty change is oversized: UPLOAD-STACK-PLAN.md plans bounded future source units; units have NOT been committed or individually verified. Preserve all earlier approved M10/M11 work.

No live HLS queue/audio hardware acceptance or arbitrary image-host trust is claimed. Relay changes remain separately owned/local. URL label spacing is a separate pending UI correction and was not changed in this batch.

--- Previous state is historical ---

# Local HLS streams metadata verified - 5 November 2026

Canonical checkout /opt/repos/streaming-radio-app remains uncommitted at base381121c1859f402c09c083a46c8dd054cc7ef97a. Published version9/rc7 is unchanged. No commit, push, signing, Play upload, relay deployment/restart, idle origin/provider activation, physical-car action or emulator launch occurred in this HLS batch. User reported Windows locking up after XC40 startup and requiring power removal; previous running-state notes below are historical. Do not start any emulator without fresh explicit authorization.

Implemented HLS TIT2/TXXX StreamArtwork paired parsing and a bounded packed-MP3 timed-ID3 bridge. Actual Media3 default MP3 sniff consumes the leading ID3, so capture occurs before factory sniff and is emitted at the first audio sample timestamp. AAC keeps its native timed-ID3 track; no duplicate bridge. Art-only changes, UTF-8, explicit clear and repeated snapshots are covered. Existing safe Nova HTTPS-host download policy and catalog fallback remain; empty clears source art but does not permanently suppress catalog art. See docs/STREAMS-METADATA.md.

Verification: immutable /opt/builds/streaming-radio-app/hls-20261105/verify-03/source, targeted.log and verify.log. Targeted12 tests pass (6 extractor,4 parser,2 timing), including all11 frozen relay fixture segments. Real Id3Decoder/factory checks preserve title/art, Apple PRIV, audio hashes and MP3 first-sample alignment; renderer tests prove no early presentation and timed clear. Full Docker verification passed: four JVM variants, release lints, instrumentation compilation, runtime/source inventory, unsigned bundle/debug build. OSV80 components,0 advisory matches. Full composed diff floor check: no violations. Two fresh isolated local reviewers (roles1 and3) found no actionable issues; this is NOT the full10-reviewer publication gate and no remote CI ran. Before future upload: run full required independent review, split oversized upload units and discover/preserve actual required remote checks.

Shared fixture source: /ssd/docker/streams-demand-only-20261105/worktree/streams/go-relay/internal/hls/testdata/artwork/. Frozen synthetic silence assets plus provenance copied into Android test resources. Coordinated with existing streams chat01a19c86-9622-7fa1-ad73-c7ac4d1470cb, with human authorization. Relay local implementation is separate and not deployed. Example CDN URLs test parsing only; actual new image hosts need evidence and reviewed policy. No live broadcaster-artwork, audible hardware, nativeTS/fMP4 artwork or exact OEM UI acceptance is claimed. Retry policy remains nominal1000ms first retry, bounded backoff.

Real RED/GREEN evidence: bridge-test-03 stock MP3 timedmetadata failure; bridge-test-04 actual factory ID3 loss failure; bridge-test-05/08 green after capture repair; verify-02 fixture oracle mutated its own input (fixed by decoding a copy), then verify-03 green. Initial fullverify01 lint required explicit Media3 ExperimentalApi opt-in, repaired without suppression. Maven429 recovery downloaded exact Robolectric artifacts from the official Google-hosted Central mirror with SHA512 checks; project remains on canonical MavenCentral and persistent cache.

Pending separate UI correction: playlist URL display must show actual URL without inserted spaces. UI unchanged in HLS work. No emulator is needed for the next documentation/review stage. Preserve all pre-existing dirty M10/M11 changes.

--- Previous notes are historical ---

# Current local test target: Volvo XC40 API30 (5 November 2026)

User requested separate XC40 test, accepted official emulator license for personal testing. Official image checksum verified, separate AVD Volvo_XC40_API30/emulator-5584/user10 running with host GPU, Vulkan disabled, DirectSound and microphone off. Existing verified debug APK installed; user test playlist imported54 stations. API33 AVD/data preserved and stopped. No app code, commits, pushes, Play/car or relay deployment changes in this task. Audio acceptance pending user listening.

This older Volvo Android11 host also centers artwork and omits metadata lines that retail XC90 Android13 shows; it is not a retail-layout replica. Private full setup/audit/recovery notes: /opt/builds/streaming-radio-app/approved-v10/XC40-SETUP-STATUS.md. Windows restart helper: approved-v10-evidence/Start-XC40-emulator.cmd under current Apps workspace. Current operator may change stations; do not overwrite their selection.

Relay consumer already exists in current Android source. Actual relay literalUTF8/apostrophe/backslash/NUL-padding encoding is compatible; no JSON-unescape rewrite needed. Empty artwork currently clears then permits catalog fallback (previous approval); authoritative suppression needs a contract decision. Live relay-artwork end-to-end and HLS artwork remain pending. Source image host allowlist is still nova.fr/www.nova.fr only.

--- Previous app build/review details ---

# Local API33 result - 5 November 2026

Completed approved M10-C/D + M11 implementation, local review/repair, Docker verification and installed rendering. No commit, push, Play upload or physical-car action. Published version9/rc7 is unchanged. Canonical checkout /opt/repos/streaming-radio-app remains uncommitted at base381121c1859f402c09c083a46c8dd054cc7ef97a.

Final tested source: /opt/builds/streaming-radio-app/approved-v10/source-03. Full verify log verify-03.log: four JVM variants, both release lints, instrumentation compilation, runtime inventory/source parity and unsigned candidate checks passed; OSV80 components,0 advisory matches; debug APK assembled. Floor final: no violations. Initial10 independent logical reviews found scope/resume/parser/network issues which were repaired; fresh final core reviews1/3/9 on source-02 plus fresh1/3/9 review of the sole source-03 UI delta found no actionable issues. triage-final.json and review-provenance.json retain precise scope; do not describe this as10 fresh full reviews on source-03 or remote CI. Future publication still requires reviewed <=500-line upload units and live required-check discovery.

Installed API33 emulator-5580/Eyyit_AAOS_API33, Driver user10, debug package only. Used existing Windows debug key for install -r; saved54 stations,1 favorite and red theme preserved. Native collection All54 -> Favorites1 -> All54 verified; returning to All retained Nova Soul. Track metadata, catalog album artwork, Loading artwork... -> collection+quality transition and shuffle/refresh overflow verified. Settings gap/label alignment/gray switch and URL-first optional-name form inspected in installed screenshots. Audio decoding/session runs, but no human listening/Volvo audio acceptance claimed.

Emulator native-host trap: automotiveDebug adds a prototype MAIN/LAUNCHER activity. The installed media host rejects that launcher app as a non-template source, leaving a blank native player despite a valid54-item session. Confirmed official AOSP MediaSource classifier and fixed only emulator component state: run-as com.impostorlabs.streamingradio.debug --user10 pm disable --user10 com.impostorlabs.streamingradio.debug/net.mspanc.twinsenradio.automotive.ImpostorPlayerActivity (actual CLI uses spaces: --user 10). It is now disabled so native player works. To restore prototype launcher: adb -s emulator-5580 shell run-as com.impostorlabs.streamingradio.debug --user 10 /system/bin/pm enable --user 10 com.impostorlabs.streamingradio.debug/net.mspanc.twinsenradio.automotive.ImpostorPlayerActivity. Do not add a production template opt-in to mask this debug-only issue. Retail manifest has no launcher. Native exact art/text/button placement remains OEM-owned; emulator centered artwork does not promise centered Volvo artwork.

Pending external integration: relay has to emit StreamArtwork with correct StreamTitle timing. This task did not modify relay. Source URLs are HTTPS exact allowlist nova.fr/www.nova.fr, initial+every redirect/default443 checked before cache/download; unknown hosts fall back catalog. Verified primary website host evidence stored nova-image-host-evidence.json. No claim every song finds catalog art. Pure parser/matcher/association tests pass; no live broadcaster-tag end-to-end proof yet.

Private captures/local debug APK: C:/Users/elliott/Documents/ChatGPT/Apps/approved-v10-evidence. Do not publish these user-playlist/broadcaster-art captures as licensed demo screenshots. User-facing app is left in native player, All stations selected, Nova Soul playing.

--- Earlier notes below are historical; this result supersedes pending status. ---

# Local approved M10/M11 implementation - 5 November 2026

This uncommitted working tree is ahead of published release9/rc7. User approved local build/API33 testing only. No commit, push, signing, Play upload or car action. Preserve Driver user10 saved library.

Implemented Settings/Add playlist corrections, native collection entries, explicit playback scope, loading-artwork description, featured-credit matching, StreamArtwork parser/association/cache fallback. Broadcaster artwork uses a separate exact trusted-host HTTPS boundary; new hosts need evidence and review. Relay changes are owned by the separate streams task; do not claim live Nova source covers until relay emits the field.

Behavior: select All stations, Favorites or an imported playlist in native Playlists; shuffle stays a player action. Browsing alone does not change the queue. Current member keeps playing when switching collections; an empty selection preserves current audio. Individual station playback keeps the selected scope and can retain a tapped outside station as one extra member. Empty-scope resume retains only the current station, never all-library fallback. Native host owns layout, overflow, text clipping and whether custom action labels render. No full-title details screen or custom driving player approved.

Evidence directory /opt/builds/streaming-radio-app/approved-v10. Queue assertion RED/GREEN logs and parser RED/GREEN XML are retained there. Initial full verify source-01 passed JVM stages but failed Media3 lint; explicit opt-in repaired. Security boundary repair/final verification/runtime rendering pending at this handoff update; consult private /opt/builds/streaming-radio-app/HANDOFF.md for completion status. Do not publish source-01. Batch exceeds500 changed lines and needs reviewed upload-unit split before future publication.

---

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

## Phone release source preparation - 10 November 2026

The phone artwork and import source stack is being prepared from public Git
baseline 76d86cc5e9b4ba2d57b7c5df6585c64f6f43f89f. Core units 1-4 passed
phone and automotive debug unit tests where applicable, and each unit passed
the prepared-source inventory check. Candidate source remains local and
unpublished. Phone version 13 / 0.3.0-rc11 remains a later Cast wiring gate;
AAOS version 11 remains unchanged.

## Phone Cast source preparation - 10 November 2026

The phone Cast sender SDK is pinned to 22.3.1 for the existing Android Gradle
Plugin toolchain. Version 23.0.0 was rejected by AAR metadata requiring AGP
9.1; no toolchain upgrade was made. SDK policy, options and privacy/Data
Safety preparation are staged. Receiver device testing and distribution
remain separate gates.
