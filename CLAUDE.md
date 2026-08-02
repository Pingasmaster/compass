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
  unit tests for both api flavors, assemble compat/future debug+release, dual APKs +
  mappings, NetBird serve of compat `app-release.apk`)
- Product flavors: `compat` (minSdk 26, `app-release.apk`) and `future` (minSdk 37,
  `app-release-future.apk`)
- `./build.sh --smoke` / `--smoke-shipped` / `--baseline-profile` / `--macrobenchmark`
  (GMD tasks use Future variant names)
- Shared flock: `~/.cache/android-apps/build.lock` (do not delete while held)
- Version bump runs on `baseVersionCode` / `baseVersionName` before the Gradle build;
  a failed build reverts the bump.

## Git workflow

Always commit and push on master once done. This repo's default branch is `master`. When working in a worktree, fast-forward the worktree branch into master and push `origin/master`, then remove the worktree - do not open a PR.