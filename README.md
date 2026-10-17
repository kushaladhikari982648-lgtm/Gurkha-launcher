<div align="center">
    <img width="320" src="ZalithLauncher/src/main/res/drawable/app_name_title.png" alt="Gurkha Launcher"></img>
</div>

<div align="center">

[![Android CI](https://github.com/kushaladhikari982648-lgtm/Gurkha-launcher/actions/workflows/android.yml/badge.svg)](https://github.com/kushaladhikari982648-lgtm/Gurkha-launcher/actions/workflows/android.yml)
[![Latest release](https://img.shields.io/github/v/release/kushaladhikari982648-lgtm/Gurkha-launcher?include_prereleases)](https://github.com/kushaladhikari982648-lgtm/Gurkha-launcher/releases)

</div>

**Gurkha Launcher** is a Minecraft: Java Edition launcher for Android. It is a fork of
[Zalith Launcher](https://github.com/ZalithLauncher/ZalithLauncher), which is based on
[PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher). Like both of them, it is free software
under the [GPL-3.0](LICENSE) license.

- **Download:** [Releases](https://github.com/kushaladhikari982648-lgtm/Gurkha-launcher/releases). Pick the `arm64-v8a` APK for most phones, `armeabi-v7a`
  for 32-bit phones, or the universal APK if you are not sure.
- It installs as its own app (`com.gurkha.launcher`) next to Zalith Launcher, with its own settings and
  game files.
- **Differences from Zalith Launcher:** runs Minecraft 26.3+ (SDL3), adds the LTW renderer, PojavLauncher's
  fix for Minecraft 1.20.5+ on 32-bit ARM devices, plus security and stability fixes. See the [release notes](https://github.com/kushaladhikari982648-lgtm/Gurkha-launcher/releases).
- **Maintainers:** publishing a release is described in [RELEASING.md](RELEASING.md).

All credit for the launcher itself goes to the Zalith Launcher and PojavLauncher developers; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the components added from Amethyst and MojoLauncher. The original
Zalith Launcher README follows.

---

<div align="center">

[![Android CI](https://github.com/ZalithLauncher/ZalithLauncher/actions/workflows/android.yml/badge.svg)](https://github.com/ZalithLauncher/ZalithLauncher/actions/workflows/android.yml)
![Downloads](https://img.shields.io/github/downloads/ZalithLauncher/ZalithLauncher/total)
[![Sponsor](https://img.shields.io/badge/sponsor-30363D?logo=GitHub-Sponsors)](https://afdian.com/a/MovTery)

</div>

- <a href="/README_ZH_CN.md">简体中文</a>丨<a href="/README-ZH_TW.md">繁體中文</a>
- Zalith Launcher is a Minecraft launcher based on [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher) that runs [Minecraft: Java Edition](https://www.minecraft.net/) on Android devices.
- Zalith Launcher aims to enhance user experience by redesigning the app interface, adding more practical features, and lowering the barrier to entry, making it easier for more people to enjoy Minecraft.

> [!WARNING]
> Attention! This project is only a **community remake**, just like PojavLauncher is developed based on [Boardwalk](https://github.com/zhuowei/Boardwalk).  
> We are planning to build the official website [zalithlauncher.cn](https://zalithlauncher.cn). Please note, our website domain ends with `.cn`, not `.com`! **That is not our site!** We do not participate in, accept, or trust that site! Please be careful to distinguish it and protect your personal privacy!

<h2 align="center">New Features</h2>

- [x] Refactored app layout for improved aesthetics and usability.
- [x] Built-in simple file manager to eliminate worries about Android permission restrictions!
- [x] Better version management, customizable game directory location, allowing settings in external storage directories.
- [x] Add more renderers, with support for renderer plugins!
- [x] Download Mods, ModPacks, resource packs, saves, and shader packs directly within the launcher!
- [x] Customizable virtual mouse icon and launcher background image.
- [x] Light and dark themes available.
- [x] And more features!

<h2 align="center">Screenshots</h2>

![Screenshot1](/.github/images/Screenshot_Launcher_Light_EN_US.jpg)
![Screenshot2](/.github/images/Screenshot_Launcher_Dark_EN_US.jpg)
![Screenshot3](/.github/images/Screenshot_Game_EN_US.jpg)

<h2 align="center">License</h2>

- Zalith Launcher is open source under the GPL v3 license.

<h2 align="center">Special Thanks</h2>

This project uses several useful code libraries. Special thanks to them!

#### Code Libraries Used by PojavLauncher

>- [Boardwalk](https://github.com/zhuowei/Boardwalk) (JVM Launcher): Unknown license / [Apache License 2.0](https://github.com/zhuowei/Boardwalk/blob/master/LICENSE) or GNU GPLv2.
>- Android Support Library: [Apache License 2.0](https://android.googlesource.com/platform/prebuilts/maven_repo/android/+/master/NOTICE.txt).
>- [GL4ES](https://github.com/PojavLauncherTeam/gl4es): [MIT License](https://github.com/ptitSeb/gl4es/blob/master/LICENSE).
>- [OpenJDK](https://github.com/PojavLauncherTeam/openjdk-multiarch-jdk8u): [GNU GPLv2 License](https://openjdk.java.net/legal/gplv2+ce.html).
>- [LWJGL3](https://github.com/PojavLauncherTeam/lwjgl3): [BSD-3 License](https://github.com/LWJGL/lwjgl3/blob/master/LICENSE.md).
>- [LWJGLX](https://github.com/PojavLauncherTeam/lwjglx) (LWJGL2 API compatibility layer for LWJGL3): Unknown license.
>- [Mesa 3D Graphics Library](https://gitlab.freedesktop.org/mesa/mesa): [MIT License](https://docs.mesa3d.org/license.html).
>- [pro-grade](https://github.com/pro-grade/pro-grade) (Java Sandbox Security Manager): [Apache License 2.0](https://github.com/pro-grade/pro-grade/blob/master/LICENSE.txt).
>- [bhook](https://github.com/bytedance/bhook) (For exit code capture): [MIT License](https://github.com/bytedance/bhook/blob/main/LICENSE).
>- [libepoxy](https://github.com/anholt/libepoxy): [MIT License](https://github.com/anholt/libepoxy/blob/master/COPYING).
>- [virglrenderer](https://github.com/PojavLauncherTeam/virglrenderer): [MIT License](https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/master/COPYING).

#### Additional Code Libraries Used by Zalith Launcher

>- [HMCL](https://github.com/HMCL-dev/HMCL) (uses some source code): [GPL-3.0 License](https://github.com/HMCL-dev/HMCL/blob/main/LICENSE)
>- [CommonMark](https://github.com/thephpleague/commonmark) (for rendering Markdown text): [BSD-3-Clause License](https://github.com/thephpleague/commonmark/blob/2.5/LICENSE)
>- [AndroidViewAnimations](https://github.com/daimajia/AndroidViewAnimations) (uses part of the animation source code): [MIT License](https://github.com/daimajia/AndroidViewAnimations/blob/master/License)
>- [TapTargetView](https://github.com/KeepSafe/TapTargetView) (for creating onboarding guides): [Apache License 2.0](https://github.com/KeepSafe/TapTargetView/blob/master/LICENSE)
