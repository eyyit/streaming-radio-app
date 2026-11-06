# HLS artwork and metadata contract

## Packed Audio snapshot

Every HLS segment starts with an ID3v2.4 tag immediately before its first audio
frame. The frame order is fixed:

1. `PRIV`: owner `com.apple.streaming.transportStreamTimestamp`, followed by
   the segment's 90 kHz Apple timestamp as an eight-byte big-endian integer.
   The wire value is modulo 2^33.
2. `TIT2`: encoding byte 3 (UTF-8), then the title.
3. `TXXX`: encoding byte 3, description `StreamArtwork`, a NUL separator,
   then the artwork URL or an empty value.

ID3 text uses literal UTF-8. It does not use ICY metadata escaping. Consumers
should match the TXXX description without case sensitivity. Title control
characters, including NUL, are removed; titles are capped at 1024 UTF-8 bytes.

Artwork is optional metadata. The accepted value is an absolute HTTP or HTTPS
URL with a host, no user information, whitespace, or control characters, and a
maximum encoded length of 2048 bytes. Invalid, NUL-containing, or oversized
values become an empty TXXX value; they do not reject audio. HLS does not fetch,
poll, or cache image bytes.

A metadata snapshot belongs to the first audio sample of its segment. A title
or artwork change closes the pending segment so the new snapshot is at the next
segment boundary. Same-title artwork replacement therefore starts a new
segment. Clearing or expiring artwork is represented by an empty URL in the
next snapshot. Existing segment bytes remain frozen. Each segment repeats its
complete snapshot so a late joiner can read metadata from the segment it enters.

The source timeline supplies title and artwork at the frame's first sample.
Artwork download completion time does not set presentation timing.

## Android consumption boundary

Android consumes timed metadata from the audio presentation timeline. It must
not time artwork presentation from an image request completing. The player may
fetch the URL after receiving the timed snapshot.

Media3's MP3 Packed Audio extractor can expose a leading ID3 tag as format
metadata rather than per-sample timed metadata. Do not assume every Media3
Packed Audio extractor emits ID3 with presentation timestamps; that behavior
remains unverified until a headless Android integration test covers the actual
player path.

## Frozen shared vectors

`internal/hls/testdata/artwork/` contains offline-generated synthetic silence
sources, frozen Packed Audio segments, and a JSON manifest. The sources are
parsed into eight complete MP3 or AAC frames per segment. They are synthetic
audio test assets, not recordings or evidence of a real broadcaster's payload. Each
manifest range was strict-decoded independently with FFmpeg. The packed files
concatenate stand-alone segments; use the manifest ranges when decoding them.

The manifest records each packed file hash, source hash, segment byte range,
expected title and artwork, expected Apple 90 kHz PTS, source audio hash, and
source frame count. `TestSharedArtworkVectors` compares production output to
the committed bytes and parses the ID3 structure with an independent oracle.
Ordinary test runs only read the frozen vectors. Intentional regeneration
requires `UPDATE_HLS_VECTORS=1`.
