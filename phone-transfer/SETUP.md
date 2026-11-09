# Create the free Firebase project

This is console setup only. The local feature has not been deployed. Do not
upload the current app changes to Play until configuration, review and tests are
complete. Existing version 11 continues to work without this project.

1. Open https://console.firebase.google.com/ and choose **Create a project**.
   Create a separate project for Streaming Radio phone transfers. An example
   name is **Impostor Labs Radio Transfer**; the globally unique project ID may
   need a suffix. Do not reuse a project with a linked billing account.
2. Google Analytics console linkage may remain **enabled**, as Elliott chose.
   This does not authorize adding Analytics code/events. Verify the plan says
   **Spark / No-cost**. Do not link billing or upgrade to Blaze.
3. In **Build → Authentication → Get started → Sign-in method**, enable
   **Anonymous** only. Users do not see a signup screen. Do not enable phone/SMS
   authentication. Anonymous identities are technical access identities, not an
   account-sync feature; they can accumulate and need monitoring.
4. In **Build → Realtime Database → Create database**, choose the data region
   intentionally, then choose **Locked mode**, never Test mode. Do not choose
   Firestore as a substitute: this implementation uses Realtime Database rules.
   Copy the exact HTTPS database URL shown in the console. It is public project
   configuration, not an administrator credential.
5. In **Project settings → General → Your apps**, register a **Web** app named
   **Streaming Radio transfer**. Copy its public `apiKey` and project ID. There
   is no need to download an SDK or create/download a service-account JSON key.
6. Standard **Firebase Hosting** is used for static files. Do not select the
   separate **App Hosting** product or Cloud Functions. The normal default site
   will use a URL such as `https://PROJECT_ID.web.app/` after deployment.

At this point stop: the database remains locked and nothing is public yet.
Provide the **project ID** and **database URL** so the prepared configuration can
be checked. Never send service-account files, private keys, passwords or tokens.
The public web API key can be entered directly in the project configuration file
when it is time to configure the feature.

## Files and configuration used after review

Server checkout: `/opt/repos/streaming-radio-app`.

- `phone-transfer/public/config.mjs`: public Firebase API key, database URL,
  enabled flag. This deliberately defaults to disabled.
- `phone-transfer/database.rules.json`: deny-default, pair-specific rules.
- `phone-transfer/firebase.json`: standard static Hosting and database rules.
- Android Gradle properties: `phoneTransferApiKey`, `phoneTransferDatabaseUrl`,
  `phoneTransferHostingUrl`. Blank defaults keep Use phone unavailable and manual
  playlist entry working. Public configuration must be supplied consistently to
  the Docker build; it is not the Play signing/publisher credential.

Deploy the reviewed Hosting and rules together only after explicit deployment
authorization. Never deploy with open rules. No Firebase Admin SDK is required.
The existing Play publisher service account is unrelated and must not be copied
into the website or app. API-key restrictions must be verified against both the
browser and Android REST clients; one browser-referrer-only key will not work in
the car. Database rules, not the public API key, authorize transfers.

## What this service does and does not do

It temporarily transfers a hosted playlist URL or file to a parked car. It does
not host users' playlists permanently. A URL still needs its existing host;
uploaded files live locally in the car and do not automatically refresh from
the phone. No audio, track history or station playback goes through Firebase.

Pairing access expires after five minutes. Successful transfers are deleted and
known abandoned transfers are retried for cleanup when clients return. If both
clients disappear, no scheduled physical-deletion deadline is promised. The
accepted free-only design can therefore accumulate inaccessible records and
anonymous identities. Monitor storage, downloads and authentication usage; do not
silently enable billing to solve a quota problem. Existing saved playlists and
manual entry must remain usable when the transfer service is unavailable.

Before release: test an actual phone-to-car transfer on the configured project,
update the published privacy notice and Play Data safety to match actual
behavior, complete the full review gates, and use a new version code. Static
mocks and offline rules tests do not establish physical Volvo rendering or live
Firebase integration acceptance.

Official references:

- https://firebase.google.com/docs/projects/billing/firebase-pricing-plans
- https://firebase.google.com/docs/auth/web/anonymous-auth
- https://firebase.google.com/docs/database/security
- https://firebase.google.com/docs/hosting
