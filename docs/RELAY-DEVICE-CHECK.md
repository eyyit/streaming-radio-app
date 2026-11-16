# Relay and device release check

Run after relay or shared playback changes. Use deterministic fixtures and a
disposable relay; never restart production as part of this check.

| ID | Applies to | Source | Observable pass condition |
|---|---|---|---|
| PHONE-RELAY-RECOVERY | Phone, tablet | Owner audit decision, 16 November 2026 UTC: retry the same station automatically | A clean relay disconnect rejoins the same station automatically; explicit pause and Cast ownership do not restart local audio. |

This decision supersedes the phone's previous pause-at-EOF behavior. Native
AAOS retains its existing queue advancement; verify its host behavior separately.
Automated evidence: [ReconnectEndOfStreamTest](../app/src/test/java/net/mspanc/twinsenradio/playback/ReconnectEndOfStreamTest.kt)
and real Media3 scheduling in
[StartupResumptionServiceTest](../app/src/test/java/net/mspanc/twinsenradio/playback/StartupResumptionServiceTest.kt).
They cover EOF, the automatic end-of-item pause, explicit pause, replacement
playback, shutdown, and station identity. Unit tests do not prove device audio.

1. Record app source manifest/commit, APK digest, package/version, device and
   OS, relay source/image digest, fixture digest, and UTC test time.
2. Build the relay and run its `make check container-smoke`. Record container
   health separately from decoded audio results. Use no live provider probes.
3. For a device, pace the local MP3 fixture at its encoded duration (the fast
   container stress fixture is unsuitable for real-time device playback).
   Expose only the disposable fixture relay via loopback SSH forwarding and
   `adb -s SERIAL reverse tcp:DEVICE_PORT tcp:LOCAL_PORT`.
4. Import a one-station M3U pointing to the forwarded fixture mount through
   the app UI. Preserve existing playlists. Play for at least 30 seconds;
   retain relay listener/byte samples and Android media-session/AudioFlinger
   output. Confirm increasing decoded output frames for the app's session.
5. Restart only the named disposable relay. Without tapping Play, verify a
   new listener, advancing bytes, and app audio output resume. Check for at
   least 30 seconds. Repeat after pausing; local audio must stay paused.
6. Repeat native AAOS through its media host, and Cast with a reachable
   physical receiver. USB loopback forwarding does not reach a Cast receiver;
   use an explicitly authorized fixture endpoint reachable by that receiver.
7. Force-stop/reopen the app and verify imported playlist persistence. Remove
   only the audit playlist and files, remove its adb reverse mapping, stop the
   audit SSH tunnel, and remove only disposable test containers/network.

Record pass/fail/unverified by candidate and variant. AudioFlinger output is
decode/output evidence, not a human listening confirmation. Record acoustic
confirmation separately. No AAOS emulator result proves Volvo rendering.
