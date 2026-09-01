# Project: Compass

## Suppressions and allowlists

Prefer fixing the root cause over `@Suppress`, lint ignores, baselines, or
allowlists. If suppression is the real choice: stop, explain, and ask the
user first (unless they already authorized that path). See Cursor rule
`android-ask-before-suppress`.

## Output formatting

NO NON-ASCII CHARACTERS ALLOWED. Em-dashes, fancy quotes, and all other non-ASCII characters are forbidden. Use ASCII , . ; : and ' " quotes only.

Enforced by `scripts/check_ascii.sh`: `./scripts/ci.sh` and `./build.sh`
hard-fail on violations. Localization resources under `src/main/res/values*`
are exempt.

## CI

Pure efreihub CI: `.efreihub/workflows/build.yml` runs `scripts/ci.sh` then
`scripts/publish_ci_release.sh`. No GitHub Actions. `build.sh` is the local
entrypoint only (deps bump, version bump, APK copy/serve). CI does not bump
the catalog or committed version; publish injects `-Pcompass.versionName` and
`-Pcompass.versionCode`. Repo is `admin/compass`. Signing files at
`/usr/local/compass-signing` or the repo root. Fat APKs:
`app-release.apk` (compat) and `app-release-future.apk` (future).

## Local pipeline

`build.sh` is shared with dustvalve_next, calc, and core. Only the PROJECT
CONFIG block (signing property, GMD annotations, Gradle tasks, extra flags)
differs. When you change shared behavior (publish, lock, JDK, version bump,
serve helper), port it to the other three the same day.

- Default `./build.sh` is the RELEASE path: bump deps, then bump
  `baseVersionCode` / `baseVersionName`, then debug lints/tests + debug APKs,
  maybe regen baseline+startup profiles, then release lint + assemble (no
  `gradle clean`), then GMD shippedsmoke, then smoke + e2e sharing one API 37
  Setup. Copies and NetBird-serves all four root APKs. A failed build reverts
  the version bump. Requires `/dev/kvm`.
- `./build.sh --debug`: bump deps, then debug lints/tests + debug APKs only.
  Skip version bump, baseline regen, release assemble, and GMD. Serve the
  debug pair (does not clobber root release APKs).
- `./build.sh --publish`: re-serve existing root release + debug APKs (four
  files). There is no `--publish-debug`.
- `./build.sh --clean`: `gradle clean` + remove root APKs, then exit. The
  default path does not clean.
- `./build.sh --force-baseline`: with the release path, always regen AOT
  profiles. Otherwise regen only when UI/startup sources are newer than the
  committed profiles. `--debug` skips baselines.
- Standalone GMD: `--smoke` / `--e2e` / `--smoke-shipped` / `--macrobenchmark`.
  Shippedsmoke is release-path only (also `--smoke-shipped`). Smoke + e2e share
  one API 37 Setup on the default path.
- Product flavors: `compat` (minSdk 26, fat ABIs armeabi-v7a/arm64-v8a/x86/x86_64/riscv64,
  `app-release.apk` / `app-debug.apk`) and `future` (minSdk 37, fat ABIs
  arm64-v8a/x86_64/riscv64, `app-release-future.apk` / `app-debug-future.apk`).
  No ABI splits; `ndk.abiFilters` on each flavor.
- Shared flock: `~/.cache/android-apps/build.lock` (do not delete while held).
  A second `./build.sh` waits.

## Alpine host / glibc distrobox

`./build.sh` re-execs into the shared Debian distrobox named `glibc`
(same box as Rust/cargo). Do not install JDK, Gradle, or the Android SDK
on the Alpine musl host. The host needs podman, distrobox, and
`/usr/local/bin/glibc-enter`. JDK 26 (Temurin) and the SDK live inside
the box; `$HOME` (and the project directory) are bind-mounted at the
same paths. GMD still needs `/dev/kvm` on the host.

## Gradle

Compass already enables the build cache and configuration-cache
(`org.gradle.configuration-cache=true`, `problems=warn`). Keep those on.
`org.gradle.workers.max=8`. Kotlin daemon heap is `-Xmx3g`.

## Git workflow

Always commit and push on master once done. This repo's default branch is `master`. When working in a worktree, fast-forward the worktree branch into master and push `origin/master`, then remove the worktree - do not open a PR.
