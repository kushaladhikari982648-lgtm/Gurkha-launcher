# Gurkha Launcher beta.9 — modern Minecraft test build

**Debug-key-signed preview, not a production-signed release.** Package: `com.gurkha.launcher.debug`.
This is a test build responding to continued 26.x failures. It is **not a claim that the reported
phone crash is resolved**: the exact game version and device crash log are still needed.

## Changes
- LTW rebuilt for ARM32, ARM64, x86 and x86_64 from upstream commit
  `3f1f4df6c80c925ea34067b1d943a84acce0184d`, the latest default-branch revision checked on 2026-11-06.
  Includes texture conversion/upload, pixel unpacking and shader qualifier fixes.
- SDL bridge initialization also runs for SDL_InitSubSystem; initialization failure is surfaced rather
  than ignored. Initial surface notifications are delivered after JNI setup. TextureView recreation
  no longer resets live SDL state. Renderer load failures stop with a readable error.
- Restored the Minecraft-inspired home/settings/shared UI from the launcher development branch,
  retaining native controls, custom backgrounds, profiles and settings.
- Standalone Rust launcher core for bounded-memory SHA-256 and atomic settings/cache replacement;
  Java fallback and concurrent/cancelled cache-write safeguards. This is not a Rust GPU renderer.
- Low-end mode: capped download/mod-scan concurrency, fewer progress updates and reduced initial heap
  allocation without changing the saved maximum RAM. No guaranteed FPS gain.
- Restored GLFW focus/lifecycle fixes and retired standalone Freedreno migration; Turnip/Zink remain.
- Keeps beta.8 process-ABI selection, Java 21/25 ARM runtimes, Android JNI dispatch instead of Java-25
  FFM, and LWJGL classpath checks.

## Install and test
Choose `armeabi-v7a` for ARM32, `arm64-v8a` for ARM64, or `universal` if uncertain.
A 64-bit CPU does not necessarily mean Android supports 64-bit apps.
Start **vanilla with LTW** before adding mods/shaders. Back up worlds and settings. Do not uninstall
just to bypass a signature conflict; that can delete app data. This does not replace/migrate the
production package automatically.

Bundled Java 25 is unavailable on **32-bit x86**. Future 26.x releases, mods, JNI behavior, phone UI
and in-world rendering are not proven by API/package checks. No WebGPU layer was added: it is not a
replacement for Minecraft's OpenGL/SDL backend and would add unverified driver requirements.

The release includes SHA256SUMS, build provenance, the renderer AAR and corresponding LTW source.
The source archives automatically provided by GitHub contain the Rust core and launcher changes.
No production updater feed is changed by this preview.
