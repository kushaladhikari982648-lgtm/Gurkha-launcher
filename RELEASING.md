# Releasing Gurkha Launcher

Releases are built and published by GitHub Actions (`.github/workflows/android.yml`) when a tag
`v<version>` is pushed. The APKs are signed with **your own release key**, which never goes into the
repository. It is stored in two repository secrets.

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
- `CURSEFORGE_API_KEY`: enables CurseForge downloads inside the launcher.

## Publishing a new version

1. In `ZalithLauncher/gradle.properties`, raise `launcher_version_code` (it must always increase, for
   example `100000` -> `100001`) and set `launcher_version_name` (for example `1.0.0-beta.2`, or
   `1.0.0` for a stable release).
2. Write the release notes in `.github/release-notes/v<version_name>.md` (Markdown).
3. Commit and push, then create and push the tag:

   ```sh
   git tag v1.0.0-beta.2
   git push origin v1.0.0-beta.2
   ```

   The tag must be exactly `v` + `launcher_version_name`, otherwise the workflow stops with an error.
4. Wait for the **Android CI** run of the tag to finish (about 10-15 minutes). It publishes a GitHub
   release with five APKs (universal, `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`), `SHA256SUMS.txt` and
   `launcher_version.json`. Versions with a `-` (like `1.0.0-beta.2`) are marked as pre-releases.
5. To make installed copies offer the update, copy the release's `launcher_version.json` into
   `launcher-info/` on the `main` branch (see [launcher-info/README.md](launcher-info/README.md)).

Re-running the workflow for a tag that is already released replaces the release's files.
