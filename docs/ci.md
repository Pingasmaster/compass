# CI workflow - what runs, what doesn't, how to interpret a failure

The actual workflow file lives at `.efreihub/workflows/build.yml`.
There is no GitHub Actions workflow. This document explains the
efreihub job and how to read its output.

## Runner

Forge-native self-hosted runner. Untrusted `run:` steps boot a
Firecracker microVM (not Docker, not the Alpine host, not bubblewrap).
The workflow comment says the tree is exported without `.git` and that
`actions/checkout` is skipped; `.efreihub/workflows/build.yml` still
lists `uses: actions/checkout@v4` as a no-op/fail-closed marker.

Guest image (`jobs.ci.guest`): GNU libc Temurin 27 EA + Android SDK,
booted read-only. `jdk: "27"`. Bytecode emitted by the build is still
JVM 26 (classfile 70) until Kotlin ships JvmTarget.JVM_27.

Operator flag this Android job still needs:

- `--sandbox-share-net` so Maven/Google downloads work (and first image bake)

Secrets are native efreihub repo secrets (see "Secrets" below); no
operator token-file/signing bind-mount flags are required for this job
anymore. GMD needs `/dev/kvm` in the guest; without it CI skips device
gates.

## Entrypoint

`./scripts/ci.sh` is the CI entrypoint. `./build.sh` is a local helper
only (dep bump, version bump, optional baseline regen, copy APKs,
HTTP serve).

`ci.sh` runs:

1. `scripts/check_ascii.sh`
2. `scripts/check_release_signing_gate.sh`
3. Debug lints + unit tests (`ktlintCheck`, `detekt`,
   `lintCompatDebug`, `lintFutureDebug`, `testCompatDebugUnitTest`,
   `testFutureDebugUnitTest`)
4. `scripts/assert_tests_ran.sh 1 app unit` - counts `<testcase>`
   elements in JVM unit-test JUnit XML and fails if zero tests ran
5. Debug APKs (`assembleCompatDebug`, `assembleFutureDebug`)
6. Release lint + assemble WITH `-Pcompass.requireReleaseSigning=true`
   (`lintCompatRelease`, `lintFutureRelease`,
   `:macrobenchmark:assembleFutureRelease`,
   `:shippedsmoke:assembleFutureRelease`, `assembleCompatRelease`,
   `assembleFutureRelease`) - no debug-signed release fallback from
   CI; this step requires the two release-signing file secrets to be
   present at the repo root (see "Secrets" below)
7. `scripts/check_elf_16k_alignment.sh`
8. When `/dev/kvm` exists: shippedsmoke + smoke + hermetic e2e on one
   API 37 GMD, each followed by `scripts/assert_tests_ran.sh`

The workflow file does not repeat `check_ascii.sh` /
`check_release_signing_gate.sh` as a separate step: `ci.sh` already
runs both, so a duplicate step would just be two names for the same
check drifting out of sync.

## Publish

After a green `ci.sh`, `scripts/publish_ci_release.sh` runs. It skips
(exit 0) only for:

- a tag ref (`refs/tags/*`) - creating the release tag must not loop
  another publish
- a push to a non-default branch - efreihub does not inject repo
  secrets there, so there is nothing to publish from

On a default-branch (`refs/heads/master`) run, a missing
`EFREIHUB_TOKEN` or a missing signing file is a **hard failure**
(nonzero exit), not a skip: efreihub injects those secrets natively
for every default-branch job, so a missing one means something is
actually broken, and CI must say so instead of quietly staying green.

When the gates pass it:

1. Picks the next patch above the highest efreihub release tag
2. Assembles two signed fat APKs with
   `-Pcompass.requireReleaseSigning=true`
   `-Pcompass.versionName=<tag>` `-Pcompass.versionCode=<unix seconds>`
3. Creates `https://efrei.app:50002/hub/api/v1/repos/admin/compass/releases`
4. Uploads `app-release.apk` (compat) and `app-release-future.apk`

The in-app updater lists that same releases URL.

## JDK

- **Guest JDK:** Temurin 27 EA (`jdk: "27"` in the workflow). `ci.sh` prefers
  a real JDK 27, then JDK 26.
- **Bytecode:** JVM 26 (class file major 70). Kotlin 2.4.20-RC3 has no
  JvmTarget.JVM_27. See repo `TODO.md`.
- **Build Tools 37.0.0** / compileSdk 37

## What it does NOT do

- It does not bump `baseVersionName` in git. Publish injects the tag
  via Gradle properties.
- It does not generate a baseline profile. That needs KVM.
  `./build.sh` on the local release path may regen profiles.
- Device gates are skipped when `/dev/kvm` is missing from the guest.

## Secrets

Three native efreihub repo secrets, injected only on default-branch
(master) job runs (see `docs/release-keys.md` for the file-secret
detail and threat model):

- `EFREIHUB_TOKEN` - env secret; write PAT for release create/upload.
  Never print it.
- `COMPASS_RELEASE_KEYSTORE` - file secret; efreihub materializes it
  at the repo root as `release-keystore.jks`.
- `COMPASS_RELEASE_PASSWORD` - file secret; efreihub materializes it
  at the repo root as `.password-signing-keys`.

All three are write-only in the efreihub secret store: the job can
consume the resulting files/env var, but nothing in this repo can read
the underlying secret values back out of efreihub. None of the three
are present on non-default-branch pushes, which is exactly why
`scripts/ci.sh`'s release-signing gate and `scripts/publish_ci_release.sh`
are only expected to succeed on `master`.

`scripts/publish_ci_release.sh` also understands `COMPASS_SIGNING_DIR`
(default `/usr/local/compass-signing`) as a secondary, manual fallback
for local testing or a non-native runner setup; it is not the expected
path on efreihub itself.
