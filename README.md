<div align="center">

<img src="branding/gurkha-icon.png" width="112" height="112" alt="Gurkha Launcher — crossed khukuris on crimson">

# Gurkha Launcher

### Minecraft: Java Edition. On Android.

A community-maintained fork of Zalith Launcher.<br>
Familiar controls. Your modpacks. More renderer choices.

[![Android CI](https://github.com/kushaladhikari982648-lgtm/Gurkha-launcher/actions/workflows/android.yml/badge.svg)](https://github.com/kushaladhikari982648-lgtm/Gurkha-launcher/actions/workflows/android.yml)
[![Releases](https://img.shields.io/github/v/release/kushaladhikari982648-lgtm/Gurkha-launcher?include_prereleases&color=C42425&label=release)](https://github.com/kushaladhikari982648-lgtm/Gurkha-launcher/releases)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-186675?logo=android&logoColor=white)
[![GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-186675)](LICENSE)

**[Download](https://github.com/kushaladhikari982648-lgtm/Gurkha-launcher/releases)** ·
**[Development builds](https://github.com/kushaladhikari982648-lgtm/Gurkha-launcher/actions/workflows/android.yml)** ·
**[Report a bug](https://github.com/kushaladhikari982648-lgtm/Gurkha-launcher/issues/new/choose)** ·
**[Build & release](RELEASING.md)**

</div>

---

## Start playing

1. Download an APK from **[Releases](https://github.com/kushaladhikari982648-lgtm/Gurkha-launcher/releases)** and choose your architecture below.
2. Install Gurkha Launcher, add your Minecraft account, and install a game version.
3. Choose a renderer supported by your device. Start without mods first, then add your packs.

| Your device | Release APK architecture | Actions artifact |
| :--- | :--- | :--- |
| Most modern Android phones | `arm64-v8a` | `GurkhaLauncher(Debug) arm64` |
| Phones with a 32-bit ARM Android system | `armeabi-v7a` | `GurkhaLauncher(Debug) arm` |
| Not sure | Universal / all | `GurkhaLauncher(Debug) all` |
| Intel devices or emulators | `x86` or `x86_64` | Matching `x86` or `x86_64` artifact |

For a development build, open a successful **Android CI** run → **Artifacts**, download the matching ZIP, and extract the APK. GitHub sign-in may be required. These are test builds, not stable releases.

> [!TIP]
> Gurkha installs separately as `com.gurkha.launcher`, alongside Zalith. Settings and game files are separate. Back up your worlds before changing builds; differently signed APKs may not install over one another.

## What makes it Gurkha?

| Familiar foundation | Gurkha additions |
| :--- | :--- |
| Version management and a built-in file manager | Separate app identity and crossed-khukuri branding |
| Customizable touch controls and backgrounds | Stronger, state-aware outlines in light and dark themes |
| In-app mods, modpacks, resource packs, worlds, and shaders | Modrinth access and credential-gated CurseForge integration |
| Multiple rendering backends | LTW, Krypton, RustGL, and experimental PowerVR Unified integration |
| Java runtime and game launch management | Low-memory work and newer LWJGL/SDL integration |

**Compatibility depends on the game version, renderer, Android system, and GPU driver.** Low-memory tuning is not a promise that every Minecraft version or modpack will run with 500 MB. Start with a lightweight configuration on entry-level devices.

### Low-end device mode

For Helio A22 / PowerVR GE8300-class phones, see **Settings → Launcher → Performance → Low-end device mode**.
It defaults on for devices reporting less than 3 GB RAM and for detected MT6761/Helio A22 hardware.
The profile reduces initial Java heap commitment, download/scanning concurrency and progress-update
frequency without changing your maximum RAM allocation or FPS limit. Existing game settings are kept.
[Exact budgets, opt-out and test plan →](docs/LOW_END_DEVICES.md)

## Downloads & CurseForge

**Modrinth does not need a CurseForge key.** CurseForge requires an approved application API key embedded when the APK is built.

> [!IMPORTANT]
> If the launcher says **“CurseForge is not available in this build”**, that APK was built without a key. Reinstalling the same APK or changing networks will not fix it. A maintainer must configure `CURSEFORGE_API_KEY` and publish a new build. Do not post keys in issues or chat.

**Maintainers:** [configure and validate CurseForge](RELEASING.md#curseforge-api-key). CI checks authentication and Minecraft mod search before building APKs. Keyless development builds are clearly marked; release builds require a working key. A successful check does not guarantee every project's files are available.

## Renderer notes

| Renderer | What to know |
| :--- | :--- |
| LTW / Krypton | Alternative backends; choose based on your device and game version. |
| RustGL | Built-in experimental OpenGL-to-GLES work aimed at low-end hardware. Shader compatibility issues remain. [Source & notes →](rustgl/README.md) |
| PowerVR Unified | Experimental ARMv7/ARM64, GLES 3 backend: Krypton with an LTW-derived feature port, **not full LTW**. [Build details & limits →](powervr/README.md) |

Passing CI means the tested code and packaging checks passed—not that gameplay has been validated on every phone. PowerVR GE8300 gameplay and Minecraft 1.21.10 compatibility are not confirmed by these builds. Existing renderer choices remain available.

<details>
<summary><strong>Interface reference · inherited Zalith layout</strong></summary>

These are **upstream Zalith screenshots**, not screenshots of the current Gurkha build. Gurkha keeps the familiar layout, with its own branding and updated outlines.

| Light theme | Dark theme |
| :---: | :---: |
| ![Upstream Zalith light launcher](.github/images/Screenshot_Launcher_Light_EN_US.jpg) | ![Upstream Zalith dark launcher](.github/images/Screenshot_Launcher_Dark_EN_US.jpg) |

</details>

## Contribute

- **Found a bug?** [Open a report](https://github.com/kushaladhikari982648-lgtm/Gurkha-launcher/issues/new/choose) with your launcher version, Android version, device/GPU, renderer, game version, and reproduction steps. Remove credentials and personal information from logs.
- **Have an idea?** Use the feature request template and explain the problem it solves.
- **Working on code?** See [build and release instructions](RELEASING.md), [RustGL](rustgl/README.md), and [PowerVR](powervr/README.md). Run the lightweight checks with `python3 -m unittest discover -s tests -v`.

## Built on community work

Gurkha is a fork of **[Zalith Launcher](https://github.com/ZalithLauncher/ZalithLauncher)**, built on **[PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher)** and the work of many contributors. Credit for that foundation belongs to its developers.

- [Third-party notices and component licenses](THIRD_PARTY_NOTICES.md)
- [Archived upstream README and acknowledgements](docs/UPSTREAM_README.md)
- Upstream language references: [简体中文](README_ZH_CN.md) · [繁體中文](README-ZH_TW.md) *(describe Zalith, not all Gurkha changes)*

Gurkha Launcher is free software under [GPL-3.0](LICENSE). Bundled components retain their own licenses; redistributed PowerVR builds must include the corresponding source described in its documentation.

<div align="center">

<sub>Community maintained · Not affiliated with or endorsed by Mojang, Microsoft, or CurseForge</sub>

</div>
