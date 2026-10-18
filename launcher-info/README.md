# launcher-info

Installed copies of Gurkha Launcher read these two files through the GitHub API
(`https://api.github.com/repos/kushaladhikari982648-lgtm/Gurkha-launcher/contents/launcher-info/...`,
set in `UrlManager.kt`). The API serves them from the repository's **default branch** (`main`), so a
change only reaches users once it is on `main`.

| File | What it does |
|---|---|
| `launcher_version.json` | The newest published version. If its `version_code` is higher than the installed one, the launcher offers the update and downloads the matching APK from the GitHub release `v<version_name>`. |
| `launcher_notice.json` | The notice shown on the launcher's home screen. It is shown again whenever `numbering` changes. |

The launcher checks for updates at most once every 5 minutes at startup, and when the user taps
"Check for updates" in the settings. Automatic checks fail silently (for example when offline);
manual checks show the error.

## After publishing a release

1. Open the release on GitHub and download its `launcher_version.json` asset. The release workflow
   generates it with the real APK sizes and the release notes.
2. Replace `launcher-info/launcher_version.json` with it and commit that to `main`.

Do this only after the release is published: an update offer that points to APKs that don't exist
yet makes the download fail.

`launcher_version.json` fields:

- `version_code` / `version_name`: must match `launcher_version_code` / `launcher_version_name` in
  `ZalithLauncher/gradle.properties` of the release.
- `title` / `description`: shown in the update dialog. `description` is Markdown. Keep all three
  languages (`en_us`, `zh_cn`, `zh_tw`); copying the English text into all three is fine. A title of
  `NONE` hides the title.
- `published_at`: `YYYY-MM-DDTHH:MM:SSZ` (UTC).
- `file_size`: APK sizes in bytes (`all` is the universal APK), only used for display.
- `pre_release`: `true` for versions like `1.1.0-beta.1`. Pre-releases are only offered to users who
  run a pre-release themselves or enabled pre-release updates in the settings.

## Posting a notice

Edit `launcher_notice.json` (`title` and `content` in all three languages; `content` is plain text
and links are clickable), set `date`, **increase `numbering` by one** and commit to `main`.
