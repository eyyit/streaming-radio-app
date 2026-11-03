# Streaming Radio project instructions

Read docs/HANDOFF-NEXT.md first, then relevant source/docs only. In the original workspace outputs/ holds canonical handoffs. Update canonical handoff and bundled copies whenever code/tools/configuration/behavior/validation changes. Preserve quota using the personal quota-routing skill and bounded cheaper workers when useful.

## Authorization

Local prototype/release preparation and emulator testing only. No commits, pushes, Play uploads, physical Volvo actions or broadcaster correspondence without explicit new authorization. Verify the named virtual target and Driver user10 before mutation: primary emulator-5580/Eyyit_AAOS_API33; separate audio diagnostic emulator-5582/StreamingRadio_AAOS_API35. Never address a physical device. Preserve user data and unrelated changes. The private53 library was removed by explicit instruction. A hosted test playlist may now be present; verify current state and preserve user imports. Never restore the private53 backup, import fixtures, or add a starter without approval.

## Official design contract

Read docs/DESIGN-SYSTEM.md before UI/mock/resource work. It applies across the entire app-owned UI/UX and future mocks. Shared AutomotiveDesign tokens implement the approved neutral charcoal art direction. Preserve approved v11 picker geometry and existing dock; do not redesign adjacent options during a fix. New structural/flow changes require mocks and explicit approval. Full-screen Settings/management and the linked PL04/PL06 Hide/Remove flow are now approved for implementation (2 November 2026); PL06 has only the header Cancel. Targeted user-requested consistency/correctness fixes are authorized. The built-in AAOS driving media host is separate and must remain available.

## Current identity and release boundary

Streaming Radio by Impostor Labs. Debug com.impostorlabs.streamingradio.debug; public candidate com.impostorlabs.streamingradio. No personal-host branding/default URLs in app. Keep upstream GPLv3-or-later copyright, provenance and notices. Public starter remains empty pending scoped rights; private emulator library does not confer redistribution rights. No ad SDK authorized. Claims must distinguish source changes, build results, installed artifact, runtime checks and human acceptance.




## Mandatory UI change discipline
The Preserve approved design / consistency section of docs/DESIGN-SYSTEM.md applies to EVERY view. Requested sizing fixes must not alter fonts, icons, labels, controls or layout elsewhere. Equivalent rows use shared typography and dimensions. Verify installed rendering against approved references before reporting completion. Settings currently use22sp labels,56dp cells and48dp switch targets; shared action text remains18sp. These are standing requirements, not optional per-task suggestions.
