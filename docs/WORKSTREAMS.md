# Streaming Radio workstream ownership

Agreed by the AAOS and phone chats on 9 November 2026 under the project owner's explicit
request to coordinate the shared repository. This agreement prevents conflicting
writes; it does not authorize commits, uploads, deployment, or product changes.

Canonical checkout: `/opt/repos/streaming-radio-app` on the configured build host.
Keep this checkout for now. Do not reset, checkout, cherry-pick or stage another
workstream's dirty files. No additional worktree has been requested or created.

## Phone/tablet workstream

The phone/tablet chat owns `app/src/phone/**`, separately
identified phone-only tests, phone mocks and its workstream handoff. The current
phone flows were approved and implemented; new structural changes still require
mock approval. Local mock files are recorded in the private workstream handoff.

Phone imports use Android's file picker or a hosted URL. They do not require QR
pairing, Firebase, or the AAOS parked settings UI. The phone media player is app
owned, unlike Volvo's native driving player. Phone Cast uses the Google Cast
SDK. Preserve receiver playback, handoff, and volume behavior. Use the paired
acceptance checks to record actual receiver results for each release.

## AAOS and shared integration workstream

The AAOS chat owns `app/src/automotive/**`,
`app/src/main/java/net/mspanc/twinsenradio/transfer/**`, `phone-transfer/**`,
and the current playback-resumption and imported-file cleanup changes.

For the current batch this chat is the SINGLE WRITER for shared Gradle files,
main manifests, data/playback classes, generic tests, source exporter, shared CI,
design rules, notices/privacy and canonical handoff. A shared change requires a
scoped patch/request and an explicit file/batch handover before the other chat
edits it. Phone-only classes must not leak into tests compiled for AAOS.

Expected future shared batches: phone public/debug application IDs; phone test
source sets and export coverage, and narrow shared media/import contracts.
Preserve PHONE_TRANSFER_* defaults, ZXing, AAOS resumption/import fixes
and all saved data. Do not assume sharing code proves either form factor works.

## Feature preservation gate

This applies to every phone, tablet and AAOS Play release, including testing
tracks, hotfixes and promotions. Use the latest owner-accepted behavior for
each surface as its baseline. A newer tester build with a regression does not
replace that baseline.

1. Maintain the [cumulative feature inventory](FEATURE-ACCEPTANCE.md) with
   each feature's platform,
   accepted behavior, source of approval and repeatable acceptance check.
   Reconcile it against accepted mocks and recordings, previous releases and
   release notes. Add every newly accepted feature before the next release.
   An incomplete inventory blocks release.
2. Before upload, record a passing result and evidence for every applicable
   check on the exact candidate. Tie the record to the source SHA, platform,
   version code and signed bundle digest. Use automated tests for behaviors
   they actually exercise and runtime evidence for gestures, rendering,
   device integrations and other behaviors they cannot establish. Test
   both variant entry points for shared features. An unchanged source file
   or green build is insufficient evidence of runtime behavior.
3. Report every failed or unverified check to Elliott and hold the affected
   release. Changing or removing an accepted behavior requires his explicit
   approval and an update to the baseline before upload. Attach the completed
   inventory and relevant evidence to the release handoff. Recheck any changed
   candidate. A promotion may reuse reproducible evidence for the same signed
   artifact when the target configuration is covered.

First entries for the inventory. These are outstanding checks, and this
list is only the starting point for the full baseline.

- **Phone cover carousel.** In the compact and full-screen player, record
  both swipe directions on the same build. Hold halfway to show both faces
  during the drag, then reach the far side to show the turn finishes before
  release. Check rounded artwork, stable bounds, and station metadata that
  settles with the incoming cover. Use the approved phone recording as
  the comparison.
- **Phone station-row artwork.** On a playlist with known station logos,
  record fast scrolling and automatic scrolling to the playing station.
  Each available logo must be in the row on its first visible frame,
  including after a cold restart. A station without usable art has its
  station-specific fallback from the first frame. Include a slow or offline
  pass with cached logos to verify the render does not depend on a new fetch.


## Shared-update and release contract

- At intake, identify how the change affects phone/tablet and AAOS. Playback,
  codec and stream metadata, station ordering, playlist parsing, favorites,
  and artwork lookup are shared by default. A platform-only change records
  the reason in its handoff.
- Put shared behavior in `app/src/main` and reusable tests in `app/src/test`.
  Trace both platform entry points. Where each platform presents the same
  data differently, wire the change into both supported surfaces in the same
  batch. AAC profile labels, for example, belong in both the phone readout
  and the AAOS metadata description. Preserve Volvo's host-owned driving UI,
  parked restrictions, phone Cast, and platform-specific navigation.
- Run the existing `tools/verify-automotive.cjs` matrix for changes that
  affect both flavors. It covers phone and automotive Debug/Release unit
  tests and release lint. Add a targeted contract test when the existing
  suite misses a specific regression. Record the source revision and evidence
  for both platforms. If one runtime surface still needs a device check,
  record that gap in the handoff before closing the work.
- Shared feature releases use one source revision and the same user-visible
  `versionName` and Play release label. Set `streamingRadioVersionName` in
  `gradle.properties` once for the pair. The Gradle check rejects flavor
  overrides and duplicate codes. The existing verifier builds both release
  bundles as well as running the two-flavor checks. Build two signed AABs for
  publication. Assign each a
  distinct, unused `versionCode` for the shared package
  `com.impostorlabs.streamingradio` and verify form-factor targeting before
  submitting to Mobile and dedicated Android Automotive OS tracks. Record
  both codes and both track statuses in the release handoff.
- When both submissions are authorized and ready, coordinate them as one
  release. Play's API can stage both track updates in one edit. App review,
  eligibility and rollout can affect when each build reaches users. A
  platform-only hotfix can ship separately. Shared source or matched release
  labels never authorize a publication or track change.

## Evidence and publication

Use immutable build exports with recorded source hashes. Reuse existing test
results only when relevant inputs match; do not repeat full suites merely because
a second chat exists. Review/test any new shared or platform-specific changes.
No source inventory regeneration or checkout/staging of another chat's work
without coordination. Full required review and exact-commit CI remain gates for
future upload; a WIP checkpoint is not publication approval.

Original phone-transfer bytes are preserved on local branch
`codex/wip-phone-transfer-20261108`, tip
`9455bbc82d1eb70516539152f1cd7e488ae44a1b`. Main remains at `479acf2` and the working
changes remain visible there intentionally. These checkpoints were not pushed.

No emulator, physical car, relay, idle broadcaster/provider, cloud deployment or
Play/GitHub upload action is authorized by this ownership agreement. User data,
keys and unrelated work remain untouched. Firebase Analytics console linkage may
remain enabled; no Analytics instrumentation was approved or added.

## Verified shared handoff - 9 November 2026
Phone flows were approved and implemented; shared playback integration is locally green on eof-candidate-03. See latest docs/HANDOFF-NEXT.md and private batch receipts. Existing ownership remains; no repository split, module extraction, worktree creation, runtime installation or code publication was performed. Phone/shared EOF fix was applied only after1578 JVM tests and both release lints passed.
