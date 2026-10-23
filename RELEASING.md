# Releasing Gurkha Launcher

Releases are built and published by GitHub Actions (`.github/workflows/android.yml`) when a tag
`v<version>` is pushed. The APKs are signed with **your own release key**, which never goes into the
repository. It is stored in two repository secrets. Release builds also require a working CurseForge API key; see below.

## One-time setup: create the signing key

> **Keep the keystore file and its password safe, and back them up.** Android only installs an update
> if it is signed with the same key as the installed app. If you lose the key, users have to uninstall
> Gurkha Launcher (losing its data) before they can install a newer version.

### 1. Create a keystore

You need `keytool`, which comes with every Java JDK:

- **PC (Windows, macOS, Linux):** install a JDK (for example [Temurin 17](https://adoptium.net/)), or use
  the one bundled with Android Studio.
- **Android phone:** install [Termux](https://f-droid.org/packages/com.termux/) from F-Droid, then run
  `pkg install openjdk-17`.

Run this command, choose a strong password and answer the questions (name, organisation, and so on):

```sh
keytool -genkeypair -v -keystore gurkha-release.jks -alias gurkha -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
```

This creates the file `gurkha-release.jks`.

### 2. Convert the keystore to Base64 text

- **Linux / Termux:** `base64 -w 0 gurkha-release.jks > gurkha-release.txt`
- **macOS:** `base64 -i gurkha-release.jks -o gurkha-release.txt`
- **Windows (PowerShell):**
  `[Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\gurkha-release.jks")) | Set-Content gurkha-release.txt`

`gurkha-release.txt` now contains one long line of text.

### 3. Add two repository secrets

On GitHub, open the repository's **Settings > Secrets and variables > Actions > New repository secret**
and add:

| Name | Value |
|---|---|
| `GURKHA_KEYSTORE_BASE64` | The full content of `gurkha-release.txt` |
| `GURKHA_KEYSTORE_PASSWORD` | The password you chose in step 1 |

Then delete `gurkha-release.txt`, but keep `gurkha-release.jks` and its password backed up somewhere
safe (not in the repository).

If a release run already failed with "Release signing key missing", open it under **Actions** and
click **Re-run all jobs**. The release is built and published as soon as the secrets exist.

Optional secrets:

- `GURKHA_KEY_ALIAS`: only needed if your key alias is not `gurkha`.
- `GURKHA_KEY_PASSWORD`: only needed if the key password differs from the keystore password
  (PKCS12 keystores always use the same password).

Required for release builds:

- `CURSEFORGE_API_KEY`: an approved application key for CurseForge (see below). Development builds
  can omit it, but then CurseForge is disabled and the download pages start on Modrinth.

### CurseForge API key

CurseForge requires an approved application API key. A missing key is a **build configuration problem**,
not an Android permission or renderer problem. Do not copy keys from other launchers, commit keys to
source control, or paste them into chat, issues, logs, or screenshots.

1. Sign in at <https://console.curseforge.com/> and request an API key for this launcher.
   Follow CurseForge's application approval process.
2. Add the approved key as a repository secret named **`CURSEFORGE_API_KEY`** under
   **Settings > Secrets and variables > Actions > New repository secret**. Paste only the key,
   without surrounding quotes. Keep any `$` characters intact; don't put the key in a shell command.
3. Run **Actions > Check CurseForge API > Run workflow**. The workflow must first be merged into
   the default branch to appear in the Actions menu. It sends an authenticated Minecraft mod search
   to the official `api.curseforge.com` endpoint, never follows redirects, and never prints the key
   or response body.
4. Once the check passes, push a commit or choose **Re-run all jobs** on the Android CI run for the
   code you want to build. Download and install the **new** APK. Existing APKs cannot acquire a key
   after installation.

Every Android CI run also has a **CurseForgeChecks** job and a status summary:

| Status | Meaning / next step |
|---|---|
| `authenticated` | Authentication and Minecraft mod search passed. This does not test every download. |
| `missing` | No usable key was supplied. Debug/PR builds warn and keep Modrinth; tagged release builds stop. |
| `invalid` | The value contains invalid header characters. Re-enter the key without quotes or extra lines. |
| `rejected` | HTTP 401/403: check app approval and whether the key was revoked or copied incorrectly. Replace the secret, then rebuild. |
| `unavailable` / `unreachable` | Rate limit, server outage, or network failure after three attempts. Retry later; do not assume the key is wrong. |
| `http-error` / `invalid-response` | Unexpected API response. Check the service and retry; redirects are deliberately blocked. |

An invalid configured key fails **both** debug and release preflight rather than silently producing
APKs with broken authentication. Pull requests from forks normally receive no repository secrets;
those development APKs intentionally have CurseForge disabled. Do not change to `pull_request_target`
or expose a key to untrusted fork code to bypass that restriction.

For a local build, put the key into `curseforge_key.txt` in the repository root (Git ignores it),
or supply the `CURSEFORGE_API_KEY` environment variable. To run the preflight locally, supply the key
through that environment variable and run:

```sh
python3 tools/check_curseforge.py --require-key
```

The checker does not read the local key file. A missing key is allowed for local/debug builds, but
CurseForge will remain unavailable. Offline regression tests need no credentials or network:

```sh
python3 -m unittest discover -s tests -v
```

## Publishing a new version

1. In `ZalithLauncher/gradle.properties`, raise `launcher_version_code` (it must always increase, for
   example `100005` -> `100006`) and set `launcher_version_name` (for example `1.0.0-beta.7`, or
   `1.0.0` for a stable release).
2. Write the release notes in `.github/release-notes/v<version_name>.md` (Markdown).
3. Commit and push, then create and push the tag:

   ```sh
   git tag v1.0.0-beta.7
   git push origin v1.0.0-beta.7
   ```

   The tag must be exactly `v` + `launcher_version_name`, otherwise the workflow stops with an error.
4. Wait for the **Android CI** run of the tag to finish (about 10-15 minutes). It publishes a GitHub
   release with five APKs (universal, `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`), `SHA256SUMS.txt` and
   `launcher_version.json`. Versions with a `-` (like `1.0.0-beta.7`) are marked as pre-releases.
5. To make installed copies offer the update, copy the release's `launcher_version.json` into
   `launcher-info/` on the `main` branch (see [launcher-info/README.md](launcher-info/README.md)).

Re-running the workflow for a tag that is already released replaces the release's files.
