# Candidate changes

## 0.3.0-rc1 source snapshot

This is a source snapshot for review; it is not a release announcement. The project contains an Android Automotive radio player, station library and search, custom station artwork, and local prototype tooling.

2November2026 modifications include registered opaque artwork-provider IDs,
canonical file-root enforcement, bounded HTTPS/redirect/image handling,
trusted/read-only Media3 controller permissions, and the Guava33.7.2 security
update. GPL and dependency notices and privacy information are accessible from
two approved Settings entries. No broader UI redesign is included.

The release configuration currently defaults to an empty playlist URL, enables online artwork unless a saved preference turns it off, and disables iTunes artwork lookup pending terms review. No starter playlist is included as an approved redistribution artifact. Station and stream rights, public package identity, security review, and vehicle validation remain unresolved. No binary is included in this source bundle.

### Continued candidate audit — 2 November 2026
- Redact credential-bearing stream URLs and exception details from reviewed playback diagnostics and trace records.
- Added five regression tests; latest debug/release unit suites each contain 174 passing tests.
- Catalog-count/field limits and full-library processing remain an open availability finding. This candidate is not publicly released.

### Catalog processing hardening — 2 November 2026
- Reject oversized source/aggregate catalogs and metadata without truncation or replacing last-good data. Validate cached inputs under the same limits.
- Reuse parsed catalogs, assign IDs using indexed lookups, slice before native browse conversion and avoid redundant queue reconciliation.
- Latest debug/release suites each pass187tests. No runtime/vehicle acceptance is implied.

### Approved parked UI implementation —2November2026
- Implement floating station-logo picker, persistent generic-logo choice and preset accent themes; keep controls geometry.
- Preserve bound networking/background decode, reduce key-format allocations and add rate-limited audio-underrun diagnostics.
- Latest187tests per variant pass; runtime checks do not establish glitch-free hardware audio or car acceptance.
