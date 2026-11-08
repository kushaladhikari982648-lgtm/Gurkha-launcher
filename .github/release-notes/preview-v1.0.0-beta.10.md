# Gurkha Launcher beta.10 — clean, rounded UI

**Debug-key-signed prerelease**, package `com.gurkha.launcher.debug`. Same development signing
certificate as beta.9. Not a production-signed release; production updater unchanged.

## The approved modern design, implemented natively
- Charcoal surfaces, soft green accents, rounded cards/buttons/dialogs, rounded switches/sliders.
- Landscape home sidebar and portrait bottom navigation; a scrollable, single-column portrait home.
- Real account/profile/version data, renderer and memory summaries, prominent launch/add-version action.
- Tools keep custom controls, game files, JAR installation, log sharing and About accessible.
- Scrollable settings categories retain all existing options rather than replacing them with mock controls.
- Default scenery/texture rendering removed; user-selected custom backgrounds remain supported.
- One short navigation fade instead of stacked bouncing animations. Honors the existing animation
  setting and Android animator-disable preference. No claimed FPS or device-frame-time improvement.

## UI corrections
- Settings pager is owned by its fragment, releases callbacks/adapters on view destruction and saves
  the selected page. Child settings actions recover their host after recreation.
- Launcher supports portrait/landscape recreation; game orientation is unchanged.
- Removed queued pivot updates and press scaling from shared controls; recycled style arrays.
- Fixed stale empty-profile icon, no-profile launch action, header touch targets/labels and title clipping.
- Cleaned activity listeners and asynchronous notice updates during recreation.

## Compatibility and installation
Retains beta.9 LTW, Rust core, Java runtimes and SDL/GLFW work. This UI release does **not** establish
that 26.x gameplay works on your device. Java 25 is still unavailable for 32-bit x86.
Choose `armeabi-v7a` for ARM32, `arm64-v8a` for ARM64, or `universal` if unsure.
Back up your worlds/settings; do not uninstall just to bypass a signature conflict.

Assets include checksums, build/native provenance, both rebuilt native AARs and corresponding sources.
CI rebuilds native dependencies; use the attached AARs or reproduce the workflow for local tag builds.
