# Streaming Radio project instructions

Read docs/HANDOFF-NEXT.md first, then relevant source/docs only. In the original workspace outputs/ holds canonical handoffs. Update canonical handoff and bundled copies whenever code/tools/configuration/behavior/validation changes. Preserve quota using the personal quota-routing skill and bounded cheaper workers when useful.

## Authorization

Local prototype/release preparation and emulator testing only. No commits, pushes, Play uploads, physical Volvo actions or broadcaster correspondence without explicit new authorization. Verify the named virtual target and Driver user10 before mutation: primary emulator-5580/Eyyit_AAOS_API33; separate audio diagnostic emulator-5582/StreamingRadio_AAOS_API35. Never address a physical device. Preserve user data and unrelated changes. The private53 library was removed by explicit instruction. A hosted test playlist may now be present; verify current state and preserve user imports. Never restore the private53 backup, import fixtures, or add a starter without approval.

## Official design contract

Read docs/DESIGN-SYSTEM.md before UI/mock/resource work. It applies across the entire app-owned UI/UX and future mocks. Shared AutomotiveDesign tokens implement the approved neutral charcoal art direction. Preserve approved v11 picker geometry and existing dock; do not redesign adjacent options during a fix. New structural/flow changes require mocks and explicit approval. Full-screen Settings/management and the linked PL04/PL06 Hide/Remove flow are now approved for implementation (2 November 2026); PL06 has only the header Cancel. Targeted user-requested consistency/correctness fixes are authorized. The built-in AAOS driving media host is separate and must remain available.

## Current identity and release boundary

Streaming Radio by Impostor Labs. Debug com.impostorlabs.streamingradio.debug; public candidate com.impostorlabs.streamingradio. No personal-host branding/default URLs in app. Keep upstream GPLv3-or-later copyright, provenance and notices. Public starter remains empty pending scoped rights; private emulator library does not confer redistribution rights. No ad SDK authorized. Claims must distinguish source changes, build results, installed artifact, runtime checks and human acceptance.




## Twinsen Radio licensing and attribution

Leszek Szczepanowski granted Elliott permission to use, modify and distribute
the Twinsen Radio code, including future changes Leszek makes, under Apache
2.0 alongside GPLv3. Keep the original permission record. Verify the rights
to other contributions, assets and SDKs separately before distribution.

Always retain "Copyright (C) 2026 Leszek Szczepanowski" and credit him as
the original author in the root NOTICE, bundled legal notices, and the
Account & About screen in both phone and AAOS builds. Use "Twinsen Radio"
with a lowercase s and include https://github.com/szczepl/twinsen-radio.
Check that both release artifacts preserve the credit before publishing.
Preserve LICENSING.md, both license texts, and third-party notices in exports.
Mark inherited files modified for Streaming Radio. For formats that cannot
carry comments, identify the changed file in NOTICE.

## Mandatory UI change discipline
The Preserve approved design / consistency section of docs/DESIGN-SYSTEM.md applies to EVERY view. Requested sizing fixes must not alter fonts, icons, labels, controls or layout elsewhere. Equivalent rows use shared typography and dimensions. Verify installed rendering against approved references before reporting completion. Approved native full-area Settings (S8 revision 2) uses36sp labels, equal minimum112dp rows scaled for font size, a36sp/80dp header, themed28sp/72dp Done, and a108x54dp switch with a44dp white thumb. Other existing settings layouts retain22sp labels/56dp rows and18sp action text. Approved P8 revision 3 keeps128dp station rows and112dp logos, with80dp action targets/56dp glyphs centered vertically on the right; reserve caption space to prevent overlaps. These are standing requirements, not optional per-task suggestions.

Public-source preparation must reconcile app/src deletions as well as additions. Do not overlay an export onto upstream while retaining obsolete source/tests. CI tools/check-source-tree.cjs checks the Git app/src inventory before compilation. Preserve executable mode for gradlew. All Done buttons use the active theme accent/onAccent; Back/Cancel stay neutral.

Before publishing a candidate, run tools/verify-automotive.cjs from a fresh checkout of that exact commit. GitHub runs the same driver. Report commit/working-tree identity and full versus preflight scope; a developer debug build is not publication verification. Do not operate the emulator unless explicitly authorized for the current task.


## Approved collection and artwork contract (5 November 2026)

M10-C/D and M11-01/02/03 are approved: Settings has a gap below its header, aligned captions and a neutral standard large switch; Add playlist puts URL first and optional Name second. Native Playlists exposes playable All stations, Favorites and imported collection entries, not separate ordered/shuffle sub-actions. A chosen collection determines Next/Previous and shuffle; browsing alone does not choose a queue. Keep the current member when switching collections; empty selections preserve audio. The native player description temporarily shows only Loading artwork... during real work, then active collection plus compact quality. The Volvo host owns placement, overflow and truncation; emulator rendering does not establish identical retail behavior. No additional custom driving player or full-title details screen is approved.

StreamArtwork is a custom ICY extension associated with StreamTitle. Prefer its safe downloaded image over catalog fallback, honor online-artwork settings and generation/cancellation guards, clear explicit empty artwork, and never reinterpret StreamUrl as artwork. The relay integration is a separate task; do not claim Nova end-to-end support until that relay actually emits the tag.
