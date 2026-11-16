# Streaming Radio feature acceptance inventory

Use this inventory with the [release gate](WORKSTREAMS.md#feature-preservation-gate).
These are baseline checks. Record results separately for each signed candidate,
including commit SHA, version code, bundle digest, device, and evidence.
Reconcile any feature absent from this inventory with the owner-approved
baseline before release. A result from a previous candidate is historical.

## Phone and tablet

- Player and library navigation. Compare portrait compact and full-screen
  playback, the portrait wheel transition in both directions, and landscape
  L3-C. Check station-details tap, compact cover tap, and vertical cover
  swipe. Keep the destination art hidden until the shared cover arrives.
  Record portrait, landscape, and tablet. See [approved design](DESIGN-SYSTEM.md)
  and the phone v17 entry in the project handoff. The phone chat accepted
  the wide-cube recording and selected L3-C for landscape.
- Horizontal carousel. Run the [release gate's drag check](WORKSTREAMS.md#feature-preservation-gate)
  and compare against the accepted second-Pixel recordings. Confirm the current
  and actual neighboring station cover share the moving edge at mid-drag.
  Verify both directions, full-width drag before release, cancelled drag,
  and next/previous with shuffle enabled. The phone chat confirmed both covers
  during the drag and a full turn before finger release.
- Library and artwork. Run the [first-frame row check](WORKSTREAMS.md#feature-preservation-gate).
  Confirm All stations and Favorites browse independently from Playing from.
  Opening the library from station details centers the active station on
  the first frame. Test manual and automatic scrolling, offline cached art,
  an unavailable logo, and station fallback in the player with album artwork
  disabled or missing. Verify artwork fit and stable layout during updates.
  The phone chat reconfirmed first-visible-frame logos on 15 November. Keep
  fallbacks stable during a row appearance. A logo that becomes available
  later may appear the next time the row enters view.
- Playlists and onboarding. Verify local M3U import and hosted URL import,
  preview station logos, cancel/error cases, Settings > Playlists, and queue
  scope after choosing a playlist. Verify the player gesture guide appears
  once with a loaded station and remains dismissed after reopening. Use
  PhoneImportFlowTest and PhoneFlowUiTest as automated evidence where covered.
  The phone chat approved the library/import flows and the first-run guide
  for implementation.
- Transport and metadata. Verify play/pause, next/previous, exact A shuffle,
  favorite heart, buffering/connecting status without layout movement, long
  metadata truncation, and AAC profile/bitrate labels. Check the playback
  buffer dialog and notification actions including app artwork while paused.
  The phone chat approved the two-line buffer mock and confirmed the final
  media card with its seek bar, shuffle, heart, next and previous actions.
- Cast. Verify receiver discovery, receiver-only playback and volume,
  representative MP3, AAC/HLS and FLAC loads, clean recovery on actual errors,
  notification routing and Disconnect while remote audio continues. Use real
  receiver evidence for playback and handoff. Verify the app resolves
  unknown stream evidence without a speculative warning. The phone chat
  confirmed speaker-only output and requested receiver volume control,
  Disconnect while the speaker keeps playing, and accurate format messages.

## AAOS

- Native media host entry, browse and playback, including station collection
  selection and next/previous/shuffle semantics. Retain parked-only boundaries
  for native management. See the repository’s
  [internal test checks](https://github.com/eyyit/streaming-radio-app/blob/main/docs/INTERNAL-TEST-CHECKS.md)
  and [AAOS mock references](https://github.com/eyyit/streaming-radio-app/blob/main/docs/SCREENSHOTS.md).
  The native v14
  entry in the handoff records the accepted return to the native media host.
- Player-only and split display, Favorites including empty, artwork picker
  and fallback, long metadata, audio profile labels, Settings, artwork
  loading descriptions, playback resumption, and legal credit. Compare
  approved native design and obtain host runtime evidence where applicable.
  The approved S8 and P8 references and collection and artwork contract are
  in [the design system](DESIGN-SYSTEM.md).
- Verify QR playlist transfer and retained provider configuration on
  transfer-enabled releases. Use instrumented host/runtime checks where
  unit tests or bundle config establish only part of the behavior. Use the
  transfer settings preserved by the paired 0.23.0 release.

## Shared and distribution

- Run the two-flavor verifier. Exercise playlist ordering and edits,
  favorites, playback queue, stream/audio format and artwork in both
  entry points. Verify saved data when updating from the prior release.
  The shared behavior and matched visible version are recorded in the
  [release contract](WORKSTREAMS.md#shared-update-and-release-contract).
- Verify public package, version and target track, correct app artwork,
  source provenance, empty bundled starter, privacy policy, and original
  author credit in both app variants. Record Play state separately from
  source and runtime checks. The owner requested the waveform listing icon
  and [original-author credit](../NOTICE) in both variants.
## Candidate receipt rows

Keep the inventory above as the behavior baseline. For every candidate, copy
these rows into its receipt and record **pass**, **fail**, or **unverified**
with evidence. Do not carry results across a changed SHA or artifact.

Receipt identity: source SHA, or the source-manifest digest when a source export has no Git SHA; variant (**phone**, **tablet**, **AAOS**);
version code when applicable; artifact SHA-256 (unsigned local test artifacts are
valid; release artifacts must be signed); build/runtime environment (device
model, OS/build, app version, receiver/host where relevant); test date; result and
evidence link for each applicable ID. Automated results identify the exact
command, SHA and log/artifact. Runtime captures identify the same candidate.
Unknown runtime evidence is **unverified** and blocks the affected release;
a passing unit test or build does not substitute for a device result.

| ID | Variant | Approval/source | Automated evidence (scope only) | Observable pass criterion and required manual evidence |
|---|---|---|---|---|
| PHONE-COVER-DRAG | Phone, tablet | [design system](DESIGN-SYSTEM.md); [phone v17 handoff](HANDOFF-NEXT.md) | [PhoneFlowUiTest](../app/src/testPhone/java/net/mspanc/twinsenradio/phone/PhoneFlowUiTest.kt) has no physical drag/render assertion. | On exact device build, record compact and full-screen both directions: both covers share the moving edge at mid-drag; turn completes before release; incoming metadata settles with cover. Include landscape L3-C and tablet if applicable. |
| PHONE-ROW-FIRST-FRAME | Phone, tablet | [first-frame baseline](WORKSTREAMS.md#feature-preservation-gate); [design system](DESIGN-SYSTEM.md) | [PhoneFlowUiTest](../app/src/testPhone/java/net/mspanc/twinsenradio/phone/PhoneFlowUiTest.kt) does not establish cold-frame rendering. | Screen-record cold launch and automatic/manual scroll: each available logo or station fallback is correct on its first visible frame; repeat offline with cached art. |
| PHONE-IMPORT-PERSIST | Phone, tablet | [playlist/import baseline](#phone-and-tablet); [shared release contract](WORKSTREAMS.md#shared-update-and-release-contract) | [PhoneImportFlowTest](../app/src/testPhone/java/net/mspanc/twinsenradio/phone/PhoneImportFlowTest.kt), [ImportedPlaylistTest](../app/src/test/java/net/mspanc/twinsenradio/data/ImportedPlaylistTest.kt) | Import local M3U and hosted HTTPS URL; preview/cancel/errors leave library unchanged; explicit import persists through process/activity restart; edit ordering/name and confirm queue scope. Capture file picker and Settings > Playlists on device. |
| PHONE-ONBOARDING | Phone, tablet | [playlist and onboarding baseline](#phone-and-tablet); [design system](DESIGN-SYSTEM.md) | [PhoneFlowUiTest](../app/src/testPhone/java/net/mspanc/twinsenradio/phone/PhoneFlowUiTest.kt) only covers its explicit UI assertions. | On device, confirm All stations and Favorites are independent from Playing from, station details opens the active station centered on first frame, and the gesture guide appears once only with a loaded station and stays dismissed after reopening. Capture first-run and return visit. |
| PHONE-PLAYBACK | Phone, tablet | [transport baseline](#phone-and-tablet); [shared release contract](WORKSTREAMS.md#shared-update-and-release-contract) | [PhoneFlowUiTest](../app/src/testPhone/java/net/mspanc/twinsenradio/phone/PhoneFlowUiTest.kt) covers selected UI/status contracts, not real streams. | On device verify play/pause, next/previous, shuffle A, favorite, metadata/profile labels, buffer and notification actions; show no layout jump during connect/buffer. Record working stream and paused state. |
| PHONE-CAST | Phone, tablet | [Cast baseline](#phone-and-tablet) | [Cast controller tests](../app/src/testPhone/java/net/mspanc/twinsenradio/phone/PhoneCastControllerTestPart1.kt), [Part2](../app/src/testPhone/java/net/mspanc/twinsenradio/phone/PhoneCastControllerTestPart2.kt), [Part3](../app/src/testPhone/java/net/mspanc/twinsenradio/phone/PhoneCastControllerTestPart3.kt), [receiver volume test](../app/src/testPhone/java/net/mspanc/twinsenradio/phone/PhoneCastLegacyRouteVolumeTest.kt) | Use an available physical Cast receiver. Record discovery, speaker-only MP3/AAC-HLS/FLAC playback, volume, phone handoff, Disconnect while receiver continues, notification routing, one real error recovery, and no speculative warning when stream format evidence is unknown. Tests with mocks are not receiver evidence. |
| AAOS-NATIVE | AAOS | [AAOS mock references](SCREENSHOTS.md); [design system](DESIGN-SYSTEM.md); [internal checks](INTERNAL-TEST-CHECKS.md) | [Automotive verifier](../tools/verify-automotive.cjs) and [instrumentation smoke](../app/src/androidTest/java/net/mspanc/twinsenradio/automotive/PrototypeSmokeInstrumentation.kt) cover only their declared assertions. | Capture real parked vehicle/head-unit entry, browse, collection selection, playback, next/previous/shuffle, Favorites empty state, artwork/fallback, settings, resumption and legal credit. Mark unavailable host checks unverified. Player-only/split rendering is a reference/mock comparison; this does not claim the prototype UI runs as the host player. |
| AAOS-TRANSFER | AAOS; phone paired where enabled | [transfer contract](WORKSTREAMS.md#phone-tablet-workstream); [privacy disclosure](privacy.html) | [PhoneTransferClientTest](../app/src/test/java/net/mspanc/twinsenradio/transfer/PhoneTransferClientTest.kt), [PrivateTransferStoreTest](../app/src/test/java/net/mspanc/twinsenradio/transfer/PrivateTransferStoreTest.kt), [QR test](../app/src/test/java/net/mspanc/twinsenradio/transfer/PairingQrTest.kt) | For a transfer-enabled paired release, demonstrate QR scan, successful file and URL handoff, import confirmation, expiry/cancel/error handling, and retained provider configuration after app restart on actual phone and AAOS host. Unit tests do not prove provider or QR runtime. |
| DISTRIBUTION-IDENTITY | Phone, tablet, AAOS | [distribution baseline](#shared-and-distribution); [privacy policy](privacy.html); [original author credit](../NOTICE) | [PhoneFlowUiTest](../app/src/testPhone/java/net/mspanc/twinsenradio/phone/PhoneFlowUiTest.kt) checks the phone launcher asset only; [Automotive verifier](../tools/verify-automotive.cjs) is build/unit evidence. | For each variant verify package, version/track, artwork, source provenance, empty bundled starter and author credit in artifact/store inspection. Record Play publication state separately; no build result proves Play state. |
| SHARED-UPDATE | Phone, tablet, AAOS | [shared update and release contract](WORKSTREAMS.md#shared-update-and-release-contract) | [Automotive verifier](../tools/verify-automotive.cjs) | Install candidate over the prior release on each supported variant; confirm saved playlists, order, favorites and playback state survive. Record both variant entry points and exact artifacts. |
