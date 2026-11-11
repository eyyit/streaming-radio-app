# Phone Cast privacy and Data Safety preparation

Status: private source preparation only. The Play Console form and hosted
privacy policy have not been submitted or published. Review these notes against
the SDK disclosure page again whenever the Cast dependency changes.

## SDK disclosure facts

The phone flavor uses `com.google.android.gms:play-services-cast-framework`
22.3.1. Google's current Cast Sender Data Safety guidance says the Cast SDK
collects App Activity about interactions with Cast devices. Examples include
generic discovery events, session events, mobile-device information and client
Cast-app information. Google says the telemetry is anonymous, encrypted in
transit, retained briefly before aggregation and deletion, and used in aggregate
to improve Cast and monitor SDK operation. The SDK disclosure says neither the
developer nor users can opt out or request deletion, and that it does not share
this telemetry with third parties or other apps.

Google's Data Safety page names the broad category `App Activity`; the likely
Play Console mapping is `App activity > App interactions`. Confirm any separate
mapping for mobile-device information against the exact SDK Index entry and
current Play Console form before submission. Do not claim that no data is
collected because the SDK describes the telemetry as anonymous.

| Play Console question | Preparation for review |
| --- | --- |
| Does the app collect data? | Yes, account for the Cast SDK on phone builds. |
| Data type | App activity / app interactions is the supported working mapping. Validate device information mapping before submitting. |
| Purpose | Analytics, for aggregate SDK operation and product improvement. |
| Required or optional | SDK reports collection for enabled Cast behavior; users cannot opt out of SDK telemetry. Cast feature use itself is user initiated. |
| Encrypted in transit | Yes, SDK says transmitted analytics are encrypted. |
| Shared | SDK says it does not share with third parties or other apps. Confirm the Play form's collection versus sharing interpretation for Google-operated logging. |
| Deletion request | No user/developer deletion control for these SDK telemetry records. |
| Linked to identity | Google says Cast telemetry is anonymous and contains no identifiers traceable to an individual user. |

This is a draft for the publisher's review, not a completed or submitted Play
Console declaration. The form is global for each Play package; the phone and
automotive artifact/package plan must be checked before completing it.

## Cast stream routing

When casting, the app sends the selected station URL and station label to the
Cast receiver. The receiver requests audio directly from the station host; the
host receives its own ordinary request information, including the receiver's
network address. The stream URL may itself contain credentials, so avoid
casting private-access streams. The app does not proxy audio through an
Impostor Labs server.

## Distribution gates

- Review [Google Cast SDK data disclosure](https://developers.google.com/cast/docs/android_sender/data_disclosure)
  and [Google Play Data Safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469).
- Check the listing's active tracks. Google exempts apps active exclusively
  on internal testing tracks from completing the Data Safety form. The
  exemption does not resolve SDK licensing or change what the app discloses.
- Leszek's Apache 2.0 grant covers his upstream Twinsen Radio code. Elliott
  authorized the Apache 2.0 option for the fork's original contributions to
  the extent he holds rights in them. Keep the third-party notices and verify
  the Google terms governing distribution of the SDK in the phone bundle.
- Confirm receiver behavior, supported station formats, stream-host access and
  any HLS CORS requirements with actual Cast hardware before distribution.
- Keep the Default Media Receiver configuration; Google says it does not
  require receiver registration. This integration does not add a custom
  receiver or a receiver-registration dependency.

## Source references

- [Google Cast SDK setup](https://developers.google.com/cast/docs/android_sender)
  documents the Android Sender dependency; current official setup example is
  22.3.1.
- [Google Cast Android integration guide](https://developers.google.com/cast/docs/android_sender/integrate)
  documents `OptionsProvider`, manifest metadata, `CastButtonFactory`, sessions,
  and `RemoteMediaClient.load(MediaLoadRequestData)`.
- [Default Media Receiver](https://developers.google.com/cast/docs/web_receiver)
  and [registration guidance](https://developers.google.com/cast/docs/registration)
  confirm the built-in receiver ID does not require registration.
- [Google Cast SDK Additional Developer Terms](https://developers.google.com/cast/docs/terms)
  require separate legal review before distribution.
