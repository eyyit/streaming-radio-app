# Candidate v6 publication preparation - 3 November 2026

Current app sources match the 0.3.0-rc4/versionCode6 candidate. Online artwork now defaults on when no choice is saved; existing explicit opt-outs and hidden-track-art choices remain authoritative. All four JVM variants pass279 tests; release lint has0errors (134 automotive/110 phonewarnings). API33 native favorite action advertises a heart; artwork catalog selection/retry/caching and approved playlist/image-editor fixes are included. The custom parked UI does not replace the OEM native media host. Real-car system-wide audio distortion remains unresolved; public-release acceptance is blocked. No speculative audio-pipeline fix was made.

User authorized a GitHub source push; the user handles Play uploads separately. Existing screenshots and approved image-editor mock are retained, including the replacement Settings image. No signing key, private playlist, car photograph, internal Google/Volvo report or raw vehicle log is included. Older notes below are historical; in particular mock-only/editor-pending and v3/v4 artifact notes do not describe this source candidate. CI result must be checked for the uploaded SHA before calling it passed.

# Local and CI verification

## Why local checks missed the CI failures

The Windows checks built the prepared developer tree. The published fork was created by overlaying that tree onto upstream; three removed upstream source/test files remained. Local tests therefore exercised different files from GitHub's clean checkout. Separately, the initial workflow assumed sdkmanager was on PATH. Unit tests cannot establish that a clean runner has an SDK installed.

The correction is to verify source identity and environment as well as app behavior, not to add redundant assertions around unchanged methods.

## One full verification command

Run `node tools/verify-automotive.cjs` in a fresh checkout of the exact candidate commit. GitHub Actions invokes that same command after explicit SDK setup. Node24, JDK17, SDK platform36 and AGP8.13's build-tools35.0.0 are required. Set JAVA_HOME and ANDROID_HOME/ANDROID_SDK_ROOT. SDKMANAGER may point to an existing SDK manager outside the SDK root; otherwise PATH/cmdline-tools are searched.

Stages: prerequisites, source parity, tooling regression tests, both debug/release automotive JVM suites, full release lint, unsigned bundle, runtime inventory and OSV scan. `--preflight` stops before Gradle/OSV and labels the receipt accordingly. No signing-key input, app installation, emulator, car operation or Play upload is part of this command.

Source-tree checks compare the prepared source inventory, Git index and actual working files. Regression fixtures include the exact stale R357 test, missing indexed source, deleted working source, untracked source, invalid manifest and out-of-root symlinks. Toolchain regressions cover a missing SDK manager, platform/build-tools/JDK mismatch, versioned tool directories, JSON receipts and clearing signing variables without changing the parent environment.

Receipts under app/build/reports/verification identify the Git commit, clean/dirty state and each stage's result. Full success requires all stages; preflight-only success must never be described as build/test/OSV success. Fresh-checkout verification is required before pushing release candidates; fast developer builds are not release verification. OSV no-match and passing tests are not a guarantee of security, station rights, audio quality or vehicle safety. Device/render/audio/driving acceptance remains separate and user authorized.

## Windows workspace helper

In the original Windows workspace, outputs/Verify-AAOS-local.ps1 and .cmd create a new checkout and configure the existing isolated toolchain. Use `-Source Server -Revision <candidate-sha>` to verify a server commit before GitHub push, or `-Source GitHub -Revision main` to verify the published branch. `-PreflightOnly` performs prerequisite/source/tooling checks only. Saved checkouts and logs are retained for inspection; the helper does not reset the working app or any emulator.

The shared Gradle task set also compiles the debug instrumentation APK so UI-regression test source is checked on fresh local/CI builds. It does not run instrumentation or start an emulator; those require separate authorized device testing.
