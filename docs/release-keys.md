# Release keys

`compass` is signed with a keystore that lives outside the repository.
The two secret files are gitignored so a contributor running `git add .`
cannot sweep them into VCS by accident.

## Native efreihub repo secrets

efreihub has two native secret kinds: an env secret (delivered as an
environment variable) and a file secret (delivered as a file, written
to a filename efreihub chooses when the secret is configured). This
repo configures two file secrets and one env secret:

| Secret name | Kind | Materialized as |
|---|---|---|
| `COMPASS_RELEASE_KEYSTORE` | file | `release-keystore.jks` (repo root) |
| `COMPASS_RELEASE_PASSWORD` | file | `.password-signing-keys` (repo root) |
| `EFREIHUB_TOKEN` | env | `$EFREIHUB_TOKEN` (write PAT) |

Properties that matter for CI honesty:

- **Values are write-only.** Once set in the efreihub secret store, no
  workflow, script, or log can read the underlying value back out -
  only consume it (sign with it, send it as a bearer token). This repo
  never tries to print or echo any of the three.
- **Default branch only.** efreihub injects all three only on jobs
  building the default branch (`master`). A job for any other branch,
  or a tag ref, does not see these files/variable at all - they are
  simply absent, not empty. `scripts/ci.sh`'s release-signing gate and
  `scripts/publish_ci_release.sh` are written to fail (not silently
  pass or skip) if a default-branch job is ever missing one of these,
  since that would mean the platform-side secret configuration broke,
  not that "there's just nothing to publish yet".
- **Filenames, not paths.** The two file secrets land directly at the
  repo root under the exact filenames `:app`'s Gradle config already
  expects (see below) - no bind mount, no copy step, no operator CLI
  flag is required for `scripts/ci.sh` to see them.

## Files

| Path | Gitignored? | Purpose |
|---|---|---|
| `release-keystore.jks` (repo root) | yes (`*.jks`) | Production signing keystore. Native `COMPASS_RELEASE_KEYSTORE` file secret on efreihub default-branch jobs. |
| `.password-signing-keys` (repo root) | yes | Store/key password (one line). Native `COMPASS_RELEASE_PASSWORD` file secret on efreihub default-branch jobs. |
| `/usr/local/compass-signing/` | n/a | Secondary, manual fallback bind-mount of the same two files (local testing / non-native runner setups only). |

`scripts/publish_ci_release.sh` also checks
`COMPASS_SIGNING_DIR` (default `/usr/local/compass-signing`) and
copies from there into the repo root when the native files are not
already there, as a fallback. `:app` reads only the repo-root copies.

Key alias is `compass`. Production assemble must pass
`-Pcompass.requireReleaseSigning=true` so a missing keystore cannot
silently fall back to debug signing. `scripts/ci.sh` always passes
that property on its release lint/assemble gate, and
`scripts/publish_ci_release.sh` always passes it on the signed
assemble: there is no CI path left that ships a debug-signed "release"
APK. A local assemble without that property still falls back to AGP
debug signing (not shippable), which is why `-Pcompass.requireReleaseSigning=true`
is required, never implied.

## Threat model

Anyone with **both** files can sign updates to the `compass` app.
The two files are intentionally split:

  - The **keystore file** is the signing key itself. Losing this means
    you cannot publish updates to the existing install base.
  - The **password file** is the password. Losing this is equivalent
    to losing the keystore.

The chmod on both files is `600`.

Splitting the two secrets across `COMPASS_RELEASE_KEYSTORE` and
`COMPASS_RELEASE_PASSWORD` on efreihub does not by itself change this
threat model - both are still needed together to sign, and both are
still scoped to default-branch jobs only, so a push to a branch other
than `master` cannot exfiltrate either one.

## Local generate (one-time, only if no production identity exists)

Do not generate a new key if `release-keystore.jks` already exists.
A new key cannot update devices that already installed a production
build signed by the current identity.

```bash
PASS=$(head -c 64 /dev/urandom | base64 -w 0 | tr -d "/+=" | cut -c1-64)
PASS=$(echo "${PASS}" | tr -d "\n")

keytool -genkeypair \
    -storetype PKCS12 \
    -alias compass \
    -keyalg RSA \
    -keysize 4096 \
    -sigalg SHA256withRSA \
    -dname "CN=compass release, OU=compass, O=compass, L=, ST=, C=US" \
    -validity 9125 \
    -keystore release-keystore.jks \
    -storepass "${PASS}" \
    -keypass  "${PASS}"

printf '%s\n' "${PASS}" > .password-signing-keys
chmod 600 release-keystore.jks .password-signing-keys
```
