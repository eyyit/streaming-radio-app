# Streams metadata

## Wire contract

Direct MP3/ICY reads StreamTitle and optional case-insensitive StreamArtwork
from IcyInfo.rawMetadata. StreamUrl retains its original meaning. Literal UTF-8
and ordinary backslashes are preserved; do not JSON-unescape titles.

Each relay HLS Packed Audio segment begins with ID3v2.4:

1. PRIV owner com.apple.streaming.transportStreamTimestamp: eight-byte big-endian
   timestamp, modulo 2^33, in 90 kHz units.
2. TIT2, UTF-8 encoding byte 3: complete title (empty clears).
3. TXXX, UTF-8 encoding byte 3: description StreamArtwork, NUL, URL or empty value.

The relay caps titles at 1024 UTF-8 bytes and artwork URLs at 2048 bytes. Android
caps recognized snapshot text at 4080 bytes and captured raw ID3 at 16 KiB.
Artwork changes do not require a changed title. Ambiguous title snapshots are
rejected. Invalid or explicitly empty artwork clears source artwork. Existing
approved catalog fallback may then supply art; empty is not a permanent opt-out.

## Timing and compatibility

HlsStreamsMetadataParser aggregates a single Metadata event before applying its
title and artwork. Media3 MetadataRenderer presents timed ID3 against playback
position. The packed-MP3 bridge emits leading ID3 at the delegate's first audio
sample timestamp, preserving the HLS sample offset and Apple timeline. Media3's
AAC extractor already emits timed ID3 and is not wrapped, avoiding duplicates.
The default factory still selects other containers. Tests cover relay packed
MP3/AAC; they do not certify artwork from every native HLS stream/container.

Snapshots apply at the presented metadata callback, not segment download time.
Images download afterward, so network latency can delay display; sample-accurate
visual synchronization is not promised. Repeated snapshots are idempotent and
art-only updates are honored. Existing generation checks/cancellation prevent
late image completion replacing a newer station/track.

## Image security

Online artwork settings remain authoritative. Existing bounded cached downloads
allow source artwork only on HTTPS port 443, exact nova.fr, www.nova.fr and
www.lesinrocks.com hosts, including every redirect. Parsed URLs are not automatically trusted. New hosts
need verified broadcaster evidence and review. Unknown hosts or failed images
use existing placeholders/catalog fallback. No embedded image bytes.

## Verification and rollout

Private evidence: /opt/builds/streaming-radio-app/hls-20261105. Tests exercise real
Media3 default-factory extraction, raw ID3 decoding, paired updates, AAC
nonduplication, unchanged audio and playback-clock presentation/clear timing.
Shared relay vectors are synthetic silence; manifests record hashes, segment
ranges and Apple timestamps. Consult HANDOFF-NEXT.md for final check status.

Local work does not deploy the relay, upload an app or establish audible hardware
acceptance. Retry policy is unchanged: nominal first retry 1000 ms with bounded
backoff, not a wall-clock guarantee. Do not activate idle origins for tests.
Do not start an emulator after the Windows lockup without fresh authorization.
