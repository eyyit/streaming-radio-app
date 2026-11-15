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
