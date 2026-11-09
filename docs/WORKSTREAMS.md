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
owned, unlike Volvo's native driving player. Direct Cast is planned, not yet
implemented; GPL/SDK licensing, receiver metadata support and required SDK
telemetry need resolution before adding a Cast dependency.

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
source sets and export coverage; narrow shared media/import contracts; and a
phoneImplementation Cast dependency only if its licensing/privacy issues are
resolved. Preserve PHONE_TRANSFER_* defaults, ZXing, AAOS resumption/import fixes
and all saved data. Do not assume sharing code proves either form factor works.

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
