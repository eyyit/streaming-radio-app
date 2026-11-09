# Phone playlist transfer

Local implementation only. No Firebase project, billing, public deployment or
production credentials are configured. `public/config.mjs` fails closed until a
separate unbilled Spark project's public web API key and HTTPS RTDB URL are set.
Anonymous Authentication must be enabled separately. The web API key is public
configuration; never add admin credentials. Rules and Hosting configuration must
be explicitly reviewed and deployed together under separate authorization.

The car creates a cryptographically random 64-hex session ID and independent
64-hex secret, a five-minute deadline, and a `waiting` transfer. The QR fragment
is `#s=SESSION_ID&k=SECRET`. The page removes it from the address bar immediately,
then authenticates anonymously and proves the secret by creating a claim. Only
the owner or claimed phone can read that session before expiry. The claimed
phone submits exactly once; the car validates and confirms before saving.

Records:

- `/transfers/{id}`: `ownerUid`, `claimSecret`, `createdAt`, `expiresAt`, `status`
  (`waiting` or `submitted`), and on submission `payload: {kind,value,name}`.
- `/claims/{id}`: `phoneUid`, `secret`. Name is always present, including `''`.

Creation timestamps must be within the preceding 30 seconds of server time;
deadline must be future and no more than 300000 ms after creation. Metadata is
immutable. Existing claims block reuse of a session ID. Never reuse random IDs.
No list access is granted. Root PATCH requests are allowed only by their
individually authorized child paths; the root itself has no read/write grant.

Delete both paths atomically using a root PATCH with `transfers/{id}: null` and
`claims/{id}: null`. Deleting the phone's claim first would remove its permission
to delete the transfer. Success means the server acknowledged both deletions.
Expiry denies reads, claims and submissions, but permits known-session cleanup
by the owner or paired phone. It does not schedule physical deletion. A client
that never returns can leave expired data behind indefinitely. The browser keeps
at most 16 pending session IDs, deadlines and an anonymous refresh credential in
local browser storage for returning cleanup; it never stores playlist contents
or the pairing secret there. Removing browser storage loses that cleanup identity.
No anonymous account auto-deletion is promised. Spark quota exhaustion and
abandoned-account/storage accumulation remain deployment considerations.

Phone and car enforce 1 MiB UTF-8 file bytes. RTDB rules bound value length to
1048576 string characters, because rules expose string length rather than a
UTF-8 byte counter; this is a bounded envelope, not an exact byte-length rule.
URL length is 4096 characters, name length 512. Hosted playlist URLs require
HTTPS without user information and are canonicalized before transfer, including
IDN hostnames and encoded path spaces. Station stream URLs inside files may
still use HTTP. Server rules validate schema and
authorization; phone validation is advisory and the car remains authoritative.
HLS media manifests are rejected as station catalogs. Files become local car
copies; hosted playlist URLs retain normal refresh behavior.

## Verification

Run `npm test` (or `node --test tests/protocol.test.mjs`), without installation.
The separate `tests/rules.emulator.mjs` requires the actual RTDB emulator. It
refuses non-loopback hosts. Use a disposable Docker container with Node 22,
Java 17 and locally installed `firebase-tools@14.15.2`, then run:

```
firebase emulators:exec --only database --project demo-phone-transfer "node tests/rules.emulator.mjs"
```

The test uses unsigned emulator-only `auth` query JWTs and the emulator's `owner`
admin token solely to seed/reset data and install the rules. None of these are
production credentials. Do not send them to a real Firebase project. Engine
4.11.2 passed nine authorization/schema/deadline/deletion scenarios locally on
6 November 2026. Five protocol tests passed. Emulator logs are ignored.

`generate-rules.mjs` is the source for `database.rules.json`; run it after rule
edits and rerun actual engine tests. Browser rendering, real Firebase project
configuration/quotas, Android integration, hardware and release privacy review
remain separate verification steps. No App Check enforcement is claimed.

## Browser tab coordination

Only one transfer tab may be active per browser profile and origin. Close the
other transfer tab and reload to continue. A tab-lifetime exclusive Web Lock
protects the cleanup records and anonymous identity, including connection
failures and cleanup retries. Browsers without Web Locks fail closed before
storage, authentication or transfer requests. Closing the tab releases the
lock; no timeout lease or automatic takeover is used.

Run `node --test tests/*.test.mjs` for protocol and multi-tab lifecycle checks.
The latter model browser contexts and Web Lock lifetime; they do not replace
native browser interoperability testing before Hosting deployment.
