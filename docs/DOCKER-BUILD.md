# Linux Docker builds

The Docker workflow builds the exported Android source on Linux without using
the Windows PowerShell upload script. It uses JDK 17, Android platform 36,
build-tools 35.0.0, and the checked-in Gradle wrapper (8.14.3). The SDK tools
archive is SHA-256 verified. Android SDK licenses must already be accepted;
the image build does not accept licenses automatically.

## Prepare the image

Install Docker with BuildKit support. The container runs the repository's
allowlisted public-source exporter into its temporary workspace, so the input
checkout can be a Git clone or an existing public-source export. Run:

```sh
ANDROID_SDK_ROOT=/path/to/android-sdk ./tools/docker-build.sh image
```

The command passes the SDK's existing `licenses/` directory to BuildKit as a
temporary secret. You can instead set `ANDROID_LICENSES_TAR` to a tar archive
containing the accepted `licenses/` directory. The license files are needed
only while building the image and are not copied into the app source.

## Build

```sh
./tools/docker-build.sh verify
./tools/docker-build.sh bundle
```

`verify` runs the repository's automotive verification driver, including its
source inventory, tooling tests, Gradle checks, and dependency scan. `bundle`
builds the unsigned automotive release AAB and automotive debug APK. Each run
gets a new directory under `dist/docker/`; the Gradle cache is kept in a Docker
volume. The source checkout is mounted read-only; only files accepted by the
public-source allowlist and secret scan are copied into the temporary build
workspace. The container runs with the caller's numeric user and group IDs.
The cache volume is scoped to that user ID and also preserves Android's local
debug signing key. That key differs from the Windows debug key, so Android
will not update an installation signed by the other key; this workflow never
uninstalls an app. Unsigned bundles are named `Streaming-Radio-unsigned.aab`.

## Optional signing

Signing is performed only when explicitly requested. Store the upload key and
password once in a private directory outside the repository:

```sh
./tools/docker-build.sh setup-signing
./tools/docker-build.sh signed-bundle
```

With no argument, setup discovers
`$HOME/creds/streaming_radio/streaming-radio-upload.p12`. Set
`STREAMING_RADIO_KEY_SOURCE` to use another default path, or pass the key path
as the optional second argument. The first `signed-bundle` run starts this
one-time interactive setup automatically when both managed signing files are
absent. In unattended use, run `setup-signing` interactively first. A partial
managed configuration fails for repair without overwriting either file.

Setup prompts for the key password without echoing it, refuses existing target
files, and writes the key and password with mode 0600 in
`~/.config/impostor-labs/streaming-radio/` (directory mode 0700). The signed
build mounts both files read-only. Passwords are not passed as command-line
arguments, Docker environment values, build arguments, or Gradle properties;
Gradle receives file paths for its existing signing configuration. The key
and password file are never copied into the source, image, artifacts, or build
logs. Anyone with access to both files can sign with that key, so protect the
private directory and its backups. The key must be a PKCS#12 keystore with the
`upload` alias and the same password for the store and key.

`signed-bundle` runs the automotive debug and release unit tests and release
lint before packaging. It disables Gradle configuration cache, checks the AAB
signature, and confirms its packaged station catalog is empty before
publishing artifacts. The resulting file is named
`Streaming-Radio-play-upload.aab`. It does not upload the bundle.
The Windows `tools/Build-Play-bundle.ps1` flow remains available and unchanged.

Host support: Linux x86_64. Checkout, private signing directory and SDK-license archive paths must not contain commas (Docker's mount-argument separator); spaces are supported. The helper rejects unsupported mount paths before starting Docker. Password setup preserves leading and trailing spaces.
