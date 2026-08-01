# Project: Compass

## Suppressions and allowlists

Prefer fixing the root cause over `@Suppress`, lint ignores, baselines, or
allowlists. If suppression is the real choice: stop, explain, and ask the
user first (unless they already authorized that path). See Cursor rule
`android-ask-before-suppress`.

## Output formatting

NO NON-ASCII CHARACTERS ALLOWED. Em-dashes, fancy quotes, and all other non-ASCII characters are forbidden. Use ASCII , . ; : and ' " quotes only.

## Git workflow

Always commit and push on master once done. This repo's default branch is `master`. When working in a worktree, fast-forward the worktree branch into master and push `origin/master`, then remove the worktree — do not open a PR.