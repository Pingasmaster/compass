# Project: Compass

## Suppressions and allowlists

Prefer fixing the root cause over `@Suppress`, lint ignores, baselines, or
allowlists. If suppression is the real choice: stop, explain, and ask the
user first (unless they already authorized that path). See Cursor rule
`android-ask-before-suppress`.

## Output formatting

NO NON-ASCII CHARACTERS ALLOWED. Em-dashes, fancy quotes, and all other non-ASCII characters are forbidden. Use ASCII , . ; : and ' " quotes only.

Enforced by `scripts/check_ascii.sh`: `./build.sh` hard-fails on violations.
No GitHub Actions workflows - all gates run locally via `./build.sh`.

## Local pipeline

- Default: `./build.sh` (ASCII, ktlint, detekt, lintCompatRelease + lintFutureRelease,
  unit tests for both api flavors, assemble compat/future debug+release, GMD
  shippedsmoke + smoke + e2e on future API 37, dual APKs + mappings, NetBird serve
  of all four root APKs)
- Product flavors: `compat` (minSdk 26, `app-release.apk` / `app-debug.apk`) and
  `future` (minSdk 37, `app-release-future.apk` / `app-debug-future.apk`).
  `./build.sh --publish` re-serves that same four-file set.
- `./build.sh --smoke` / `--e2e` / `--smoke-shipped` / `--macrobenchmark`
  (standalone GMD; default `./build.sh` already runs smoke + e2e + shippedsmoke.
  Baselines regenerate on every default `./build.sh`, skipped by `--debug`)
- Shared flock: `~/.cache/android-apps/build.lock` (do not delete while held)
- Version bump runs on `baseVersionCode` / `baseVersionName` before the Gradle build;
  a failed build reverts the bump.

## Shared build.sh

`build.sh` is the same script across dustvalve_next, calc, compass,
and core except the PROJECT CONFIG block (signing
property, GMD annotations, Gradle tasks, extra flags). When you change
shared behavior (publish, lock, JDK, version bump, serve helper), port it
to the other four the same day.

## Git workflow

Always commit and push on master once done. This repo's default branch is `master`. When working in a worktree, fast-forward the worktree branch into master and push `origin/master`, then remove the worktree - do not open a PR.