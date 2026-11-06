# Frozen relay HLS artwork vectors

Copied 2026-11-05 from streams/go-relay/internal/hls/testdata/artwork.
These are offline-generated synthetic silence sources and frozen packed-audio
segments, not broadcaster recordings. manifest.json preserves the upstream
SHA-256 hashes and expected ranges. Android tests never regenerate vectors.
The relay contract is captured in HLS_ARTWORK.md alongside these vectors.
