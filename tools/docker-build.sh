#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

fail() {
    printf 'Docker build stopped: %s\n' "$1" >&2
    exit 2
}

check_mount_path() {
    case "$1" in *','*) fail 'Docker mount paths cannot contain commas' ;; esac
}
mode=${1:-}
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
private_dir=${STREAMING_RADIO_PRIVATE_DIR:-"${HOME:?HOME must be set}/.config/impostor-labs/streaming-radio"}
image=${STREAMING_RADIO_DOCKER_IMAGE:-streaming-radio-android:local}
output="$repo/dist/docker"
check_mount_path "$repo"

case "$mode" in
    image|verify|bundle|signed-bundle|setup-signing) ;;
    *) fail 'usage: tools/docker-build.sh image|verify|bundle|signed-bundle|setup-signing [keystore-path]' ;;
esac

default_key_source() {
    printf '%s' "${STREAMING_RADIO_KEY_SOURCE:-${HOME:?HOME must be set}/creds/streaming_radio/streaming-radio-upload.p12}"
}

setup_signing() {
    local key_source resolved_repo resolved_key password confirmation
    key_source=${2:-$(default_key_source)}
    [ ! -L "$key_source" ] && [ -f "$key_source" ] && [ -r "$key_source" ] || fail 'keystore must be a readable regular file, not a symlink'
    resolved_repo=$(realpath -- "$repo")
    resolved_key=$(realpath -- "$key_source")
    case "$resolved_key" in "$resolved_repo"/*) fail 'keep the source keystore outside the repository' ;; esac

    resolved_repo=$(realpath -- "$repo")
    [ ! -L "$private_dir" ] || fail 'private signing directory cannot be a symlink'
    private_dir=$(realpath -m -- "$private_dir")
    check_mount_path "$private_dir"
    case "$private_dir" in "$resolved_repo"|"$resolved_repo"/*) fail 'private signing directory must stay outside the repository' ;; esac
    mkdir -p -- "$private_dir"
    [ ! -L "$private_dir" ] || fail 'private signing directory cannot be a symlink'
    private_dir=$(realpath -- "$private_dir")
    check_mount_path "$private_dir"
    case "$private_dir" in "$resolved_repo"|"$resolved_repo"/*) fail 'private signing directory must stay outside the repository' ;; esac
    chmod 0700 -- "$private_dir"
    setup_key_target="$private_dir/upload.p12"
    setup_password_target="$private_dir/upload-password"
    for target in "$setup_key_target" "$setup_password_target"; do
        [ ! -e "$target" ] && [ ! -L "$target" ] || fail 'signing setup refuses to overwrite an existing file'
    done

    [ -r /dev/tty ] || fail 'setup-signing requires an interactive terminal'
    IFS= read -r -s -p 'Upload key password: ' password </dev/tty
    printf '\n' >/dev/tty
    IFS= read -r -s -p 'Confirm password: ' confirmation </dev/tty
    printf '\n' >/dev/tty
    [ -n "$password" ] && [ "$password" = "$confirmation" ] || fail 'passwords were empty or did not match'

    setup_stage=$(mktemp -d "$private_dir/.setup-XXXXXXXX")
    install -m 0600 -- "$key_source" "$setup_stage/upload.p12"
    printf '%s' "$password" > "$setup_stage/upload-password"
    chmod 0600 -- "$setup_stage/upload-password"
    ln -- "$setup_stage/upload.p12" "$setup_key_target"
    setup_key_created=1
    ln -- "$setup_stage/upload-password" "$setup_password_target"
    setup_password_created=1
    setup_committed=1
    rm -rf -- "$setup_stage"
    setup_stage=''
    unset password confirmation
    printf 'Signing files saved under %s with mode 0600.\n' "$private_dir"
}

setup_stage=''
setup_key_target=''
setup_password_target=''
setup_key_created=0
setup_password_created=0
setup_committed=0
cleanup_signing() {
    [ -z "$setup_stage" ] || rm -rf -- "$setup_stage"
    if [ "$setup_committed" -eq 0 ]; then
        [ "$setup_key_created" -eq 0 ] || rm -f -- "$setup_key_target"
        [ "$setup_password_created" -eq 0 ] || rm -f -- "$setup_password_target"
    fi
}

if [ "$mode" = setup-signing ]; then
    trap cleanup_signing EXIT
    setup_signing "$@"
    trap - EXIT
    exit 0
fi

if [ "$mode" = signed-bundle ]; then
    key_file="$private_dir/upload.p12"
    password_file="$private_dir/upload-password"
    if [ ! -e "$key_file" ] && [ ! -L "$key_file" ] &&
       [ ! -e "$password_file" ] && [ ! -L "$password_file" ]; then
        if [ -t 0 ]; then
            bash "$repo/tools/docker-build.sh" setup-signing
        else
            fail 'signing files are missing; run tools/docker-build.sh setup-signing interactively first'
        fi
    fi
fi

if [ "$mode" = image ]; then
    license_tar=${ANDROID_LICENSES_TAR:-}
    temp_license_tar=''
    build_context=$(mktemp -d)
    cleanup_image() {
        rm -rf -- "$build_context"
        [ -z "$temp_license_tar" ] || rm -f -- "$temp_license_tar"
    }
    trap cleanup_image EXIT

    if [ -z "$license_tar" ]; then
        sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
        [ -n "$sdk_root" ] && [ -d "$sdk_root/licenses" ] || fail 'set ANDROID_LICENSES_TAR or ANDROID_SDK_ROOT to an SDK with accepted licenses'
        temp_license_tar=$(mktemp)
        tar -czf "$temp_license_tar" -C "$sdk_root" licenses
        license_tar=$temp_license_tar
    fi
    check_mount_path "$license_tar"
    [ -f "$license_tar" ] && [ -r "$license_tar" ] || fail 'Android licenses archive is unreadable'
    cp -- "$repo/docker/Dockerfile" "$build_context/Dockerfile"
    cp -- "$repo/docker/entrypoint.sh" "$build_context/entrypoint.sh"
    docker build \
        --secret "id=android_licenses,src=$license_tar" \
        --tag "$image" \
        --file "$build_context/Dockerfile" \
        "$build_context"
    exit 0
fi

command -v docker >/dev/null 2>&1 || fail 'Docker is not installed or not on PATH'
pending=''
cleanup_run() {
    [ -z "$pending" ] || rm -rf -- "$pending"
}
trap cleanup_run EXIT

uid=$(id -u)
gid=$(id -g)
cache_volume="streaming-radio-gradle-cache-$uid"
mkdir -p -- "$output"
pending=$(mktemp -d "$output/.pending-$mode-XXXXXXXX")
run_id=${pending##*.pending-$mode-}

docker_args=(
    run --rm
    --user "$uid:$gid"
    --cap-drop=ALL
    --security-opt=no-new-privileges
    --mount "type=bind,src=$repo,dst=/src,readonly"
    --mount "type=bind,src=$pending,dst=/out"
    --mount "type=volume,src=$cache_volume,dst=/gradle-cache"
    --env "MODE=$mode"
)

if [ "$mode" = signed-bundle ]; then
    [ ! -L "$private_dir" ] && [ -d "$private_dir" ] || fail 'signing directory is missing or unsafe'
    private_dir=$(realpath -- "$private_dir")
    check_mount_path "$private_dir"
    resolved_repo=$(realpath -- "$repo")
    case "$private_dir" in "$resolved_repo"|"$resolved_repo"/*) fail 'private signing directory must stay outside the repository' ;; esac
    [ "$(stat -c '%u:%a' -- "$private_dir")" = "$uid:700" ] || fail 'signing directory must be caller-owned with mode 0700'
    key_file="$private_dir/upload.p12"
    password_file="$private_dir/upload-password"
    [ ! -L "$key_file" ] && [ -f "$key_file" ] && [ -r "$key_file" ] || fail 'run setup-signing first; keystore is missing or unsafe'
    [ ! -L "$password_file" ] && [ -f "$password_file" ] && [ -r "$password_file" ] || fail 'run setup-signing first; password file is missing or unsafe'
    [ "$(stat -c '%u:%a' -- "$key_file")" = "$uid:600" ] || fail 'keystore must be caller-owned with mode 0600'
    [ "$(stat -c '%u:%a' -- "$password_file")" = "$uid:600" ] || fail 'password file must be caller-owned with mode 0600'
    docker_args+=(
        --mount "type=bind,src=$key_file,dst=/run/secrets/upload.p12,readonly"
        --mount "type=bind,src=$password_file,dst=/run/secrets/upload-password,readonly"
        --env STREAMING_RADIO_KEYSTORE=/run/secrets/upload.p12
        --env STREAMING_RADIO_KEY_ALIAS=upload
        --env STREAMING_RADIO_STORE_PASSWORD_FILE=/run/secrets/upload-password
        --env STREAMING_RADIO_KEY_PASSWORD_FILE=/run/secrets/upload-password
    )
fi

docker_args+=("$image")
docker "${docker_args[@]}"
destination="$output/$mode-$run_id"
mv -- "$pending" "$destination"
pending=''
printf 'Build artifacts saved under %s\n' "$destination"
