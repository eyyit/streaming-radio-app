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

source_root=${SOURCE_ROOT:-/src}
output_root=${OUTPUT_ROOT:-/out}
work_root=${WORK_ROOT:-/work}
home_tmp=${HOME_TMP_DIR:-/tmp/home}
gradle_cache_dir=${GRADLE_CACHE_DIR:-/gradle-cache}
[ -d "$source_root" ] || fail 'readonly source mount is missing'
[ -d "$output_root" ] || fail 'artifact output mount is missing'
mkdir -p "$work_root" "$home_tmp" "$gradle_cache_dir" "$ANDROID_USER_HOME"
node "$source_root/tools/export-public-source.cjs" --output "$work_root/source"
cd "$work_root/source"

retain_verify_tree() {
    verify_tree=$1
    verify_required=$2
    if [ -d "$verify_tree" ]; then
        verify_parent="$output_root/$(dirname "$verify_tree")"
        if ! mkdir -p "$verify_parent" ||
            ! cp -R "$verify_tree" "$verify_parent/"; then
            printf 'Could not retain verification evidence: %s\n' \
                "$verify_tree" >&2
            verify_copy_error=1
        fi
    elif [ "$verify_required" -eq 1 ]; then
        printf 'Required verification evidence is missing: %s\n' \
            "$verify_tree" >&2
        verify_copy_error=1
    fi
}

publish_verify_artifacts() {
    verify_stage=$(mktemp -d "$output_root/.verify-artifacts-XXXXXXXX") ||
        return 1
    if ! cp "$bundle" "$verify_stage/Streaming-Radio-unsigned.aab" ||
        ! cp "$phone_bundle" \
            "$verify_stage/Streaming-Radio-phone-unsigned.aab" ||
        ! cp "$apk" "$verify_stage/Streaming-Radio-debug.apk" ||
        ! (cd "$verify_stage" &&
            sha256sum Streaming-Radio-unsigned.aab \
                Streaming-Radio-phone-unsigned.aab \
                Streaming-Radio-debug.apk > SHA256SUMS); then
        rm -rf -- "$verify_stage"
        return 1
    fi
    if ! mv "$verify_stage"/* "$output_root/"; then
        rm -f -- "$output_root/Streaming-Radio-unsigned.aab" \
            "$output_root/Streaming-Radio-phone-unsigned.aab" \
            "$output_root/Streaming-Radio-debug.apk" \
            "$output_root/SHA256SUMS"
        rm -rf -- "$verify_stage"
        return 1
    fi
    if ! rmdir "$verify_stage"; then
        rm -f -- "$output_root/Streaming-Radio-unsigned.aab" \
            "$output_root/Streaming-Radio-phone-unsigned.aab" \
            "$output_root/Streaming-Radio-debug.apk" \
            "$output_root/SHA256SUMS"
        rm -rf -- "$verify_stage"
        return 1
    fi
}

retain_verify_evidence() {
    verify_exit_status=$?
    trap - EXIT
    if [ "$mode" != verify ]; then
        exit "$verify_exit_status"
    fi

    verify_copy_error=0
    if [ -f SOURCE-MANIFEST.json ] && [ -f SOURCE-MANIFEST.sha256 ]; then
        if ! cp SOURCE-MANIFEST.json SOURCE-MANIFEST.sha256 "$output_root/"; then
            printf 'Could not retain source manifest in %s\n' \
                "$output_root" >&2
            verify_copy_error=1
        fi
    elif [ "$verify_exit_status" -eq 0 ]; then
        printf 'Required source manifest is missing.\n' >&2
        verify_copy_error=1
    fi

    if [ -f app/build/reports/verification/verification.json ]; then
        retain_verify_tree app/build/test-results 1
        retain_verify_tree app/build/reports 1
        retain_verify_tree app/build/outputs/bundle 1
    elif [ "$verify_exit_status" -eq 0 ]; then
        printf 'Required verification receipt is missing.\n' >&2
        verify_copy_error=1
    else
        retain_verify_tree app/build/test-results 0
        retain_verify_tree app/build/reports 0
        retain_verify_tree app/build/outputs/bundle 0
    fi

    if [ "$verify_exit_status" -eq 0 ] && [ "$verify_copy_error" -eq 0 ]; then
        if ! publish_verify_artifacts; then
            printf 'Could not publish verified artifacts.\n' >&2
            verify_copy_error=1
        fi
    fi
    if [ "$verify_exit_status" -ne 0 ]; then
        exit "$verify_exit_status"
    fi
    if [ "$verify_copy_error" -ne 0 ]; then
        exit 1
    fi
    exit 0
}
if [ "$mode" = verify ]; then
    trap retain_verify_evidence EXIT
fi

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
if [ "$mode" = verify ]; then
    phone_bundle=app/build/outputs/bundle/phoneRelease/app-phone-release.aab
    [ -s "$phone_bundle" ] || fail 'phone release bundle was not produced'
fi

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

if [ "$mode" != verify ]; then
    if [ "$mode" = signed-bundle ]; then
        cp "$bundle" "$output_root/Streaming-Radio-play-upload.aab"
    else
        cp "$bundle" "$output_root/Streaming-Radio-unsigned.aab"
    fi
    cp "$apk" "$output_root/Streaming-Radio-debug.apk"
    for report in \
        app/build/reports/release/runtime-components.json \
        app/build/reports/release/dependency-security-scan.json \
        app/build/reports/verification/verification.json; do
        if [ -f "$report" ]; then
            mkdir -p "$output_root/$(dirname "$report")"
            cp "$report" "$output_root/$report"
        fi
    done
fi
