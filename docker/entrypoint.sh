#!/bin/sh
set -eu
umask 077

fail() {
    printf 'Docker build stopped: %s\n' "$1" >&2
    exit 1
}

mode=${MODE:-}
case "$mode" in
    verify)
        ;;
    signed-bundle)
        [ "${STREAMING_RADIO_KEYSTORE:-}" = /run/secrets/upload.p12 ] || fail 'signing keystore mount is missing'
        [ "${STREAMING_RADIO_STORE_PASSWORD_FILE:-}" = /run/secrets/upload-password ] || fail 'signing password file mount is missing'
        [ "${STREAMING_RADIO_KEY_PASSWORD_FILE:-}" = /run/secrets/upload-password ] || fail 'signing password file mount is missing'
        [ -r /run/secrets/upload.p12 ] && [ -r /run/secrets/upload-password ] || fail 'signing files are unreadable'
        [ "${STREAMING_RADIO_KEY_ALIAS:-}" = upload ] || fail 'signing alias is missing'
        ;;
    bundle)
        ;;
    *) fail 'unknown build mode' ;;
esac

[ -d /src ] || fail 'readonly source mount is missing'
[ -d /out ] || fail 'artifact output mount is missing'
mkdir -p /work /tmp/home /gradle-cache "$ANDROID_USER_HOME"
node /src/tools/export-public-source.cjs --output /work/source
cd /work/source

# The source-tree verifier compares its manifest with Git's index. The export
# is deliberately metadata-free, so create only a temporary source index.
git init --quiet
git add -- app/src
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties

if [ "$mode" = verify ]; then
    env -u STREAMING_RADIO_KEYSTORE \
        -u STREAMING_RADIO_STORE_FILE \
        -u STREAMING_RADIO_STORE_PASSWORD \
        -u STREAMING_RADIO_STORE_PASSWORD_FILE \
        -u STREAMING_RADIO_KEY_ALIAS \
        -u STREAMING_RADIO_KEY_PASSWORD \
        -u STREAMING_RADIO_KEY_PASSWORD_FILE \
        node tools/verify-automotive.cjs
    env -u STREAMING_RADIO_KEYSTORE \
        -u STREAMING_RADIO_STORE_FILE \
        -u STREAMING_RADIO_STORE_PASSWORD \
        -u STREAMING_RADIO_STORE_PASSWORD_FILE \
        -u STREAMING_RADIO_KEY_ALIAS \
        -u STREAMING_RADIO_KEY_PASSWORD \
        -u STREAMING_RADIO_KEY_PASSWORD_FILE \
        ./gradlew --no-daemon --parallel \
        :app:assembleAutomotiveDebug
elif [ "$mode" = signed-bundle ]; then
    max_workers=$(getconf _NPROCESSORS_ONLN)
    ./gradlew --no-daemon --no-configuration-cache \
        --max-workers="$max_workers" \
        :app:testAutomotiveDebugUnitTest \
        :app:testAutomotiveReleaseUnitTest \
        :app:lintAutomotiveRelease
    ./gradlew --no-daemon --no-configuration-cache --parallel \
        --max-workers="$max_workers" \
        :app:bundleAutomotiveRelease \
        :app:assembleAutomotiveDebug \
        :app:writeAutomotiveReleaseInventory
else
    max_workers=$(getconf _NPROCESSORS_ONLN)
    env -u STREAMING_RADIO_KEYSTORE \
        -u STREAMING_RADIO_STORE_FILE \
        -u STREAMING_RADIO_STORE_PASSWORD \
        -u STREAMING_RADIO_STORE_PASSWORD_FILE \
        -u STREAMING_RADIO_KEY_ALIAS \
        -u STREAMING_RADIO_KEY_PASSWORD \
        -u STREAMING_RADIO_KEY_PASSWORD_FILE \
        ./gradlew --no-daemon --parallel --max-workers="$max_workers" \
        :app:bundleAutomotiveRelease \
        :app:assembleAutomotiveDebug \
        :app:writeAutomotiveReleaseInventory
fi

bundle=app/build/outputs/bundle/automotiveRelease/app-automotive-release.aab
[ -s "$bundle" ] || fail 'release bundle was not produced'

if [ "$mode" = signed-bundle ]; then
    jarsigner -verify "$bundle" >/dev/null 2>&1 || fail 'release bundle signature verification failed'
    python3 - "$bundle" <<'PY'
import json
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as archive:
  names = archive.namelist()
  signatures = [name for name in names if name.startswith('META-INF/')
                and name.lower().endswith(('.rsa', '.dsa', '.ec'))]
  if not signatures:
    raise SystemExit('Signed bundle is missing a signature block.')
  catalogs = [name for name in names
              if name.endswith('/assets/stations.json')]
  if len(catalogs) != 1 or json.loads(archive.read(catalogs[0])) != []:
    raise SystemExit('Signed bundle must contain one empty station catalog.')
PY
fi

apk=app/build/outputs/apk/automotive/debug/app-automotive-debug.apk
[ -s "$apk" ] || fail 'automotive debug APK was not produced'
if [ "$mode" = signed-bundle ]; then
    cp "$bundle" /out/Streaming-Radio-play-upload.aab
else
    cp "$bundle" /out/Streaming-Radio-unsigned.aab
fi
cp "$apk" /out/Streaming-Radio-debug.apk

for report in \
    app/build/reports/release/runtime-components.json \
    app/build/reports/release/dependency-security-scan.json \
    app/build/reports/verification/verification.json; do
    if [ -f "$report" ]; then
        mkdir -p "/out/$(dirname "$report")"
        cp "$report" "/out/$report"
    fi
done
