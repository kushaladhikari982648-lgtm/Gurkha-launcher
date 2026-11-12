# Gurkha Launcher beta.11 — video wallpapers, skins & capes, gameplay recording

**Debug-key-signed prerelease**, package `com.gurkha.launcher.debug`, version code `100010`.
Uses the same development signing key as beta.10. This is not a production-signed release;
the production updater is unchanged. Back up worlds and settings before installing.

## Downloads
- **arm64-v8a**: 64-bit ARM Android devices.
- **armeabi-v7a**: 32-bit ARM Android devices.
- **universal**: all supported architectures, if unsure (larger download).
- Separate x86 and x86_64 APKs are also attached.

## New features
- **Video wallpapers:** Settings → Custom background → Main menu. Import and select muted,
  looping MP4/M4V, WebM, MKV, 3GP or MOV files. Codec support depends on the device. Prefer
  720p H.264 MP4; limits are 256 MiB and 1920×1080 pixels per frame. Playback releases its
  decoder when leaving the launcher. Static backgrounds remain supported; controls-editor
  and in-game backgrounds remain image-only.
- **Skins & capes:** Home → Tools → Skins & capes. Import, preview, select/deselect and delete
  local PNG textures, then export a CustomSkinLoader pack. Install a compatible CSL mod
  separately and extract the pack into the correct instance game folder. This is not an
  official account upload, does not grant official capes, and does not automatically make
  other players see your textures. Compatible mod availability varies by Minecraft version.
- **Gameplay-only recorder:** In-game menu → Record gameplay / Stop recording. Captures the
  native game surface, excluding launcher touch controls and Android menus. Minecraft's
  own HUD/chat/menus remain visible. Silent H.264 MP4, up to 480p / 15 fps, maximum ten minutes.
  Stops when leaving the game. Use Recordings to share/export or delete videos. Capture
  may reduce FPS; unsupported devices report an error rather than recording the full screen.
- Retains the approved charcoal/mint rounded UI and all existing settings.

## RustGL and compatibility
- Bundles the user's newest available RustGL source, commit
  `70141c9602101ebdfb65296f7642bc69ac508ebb`, with original attribution retained.
- RustGL is experimental, uses GLES rather than Vulkan, and is bundled for all four ABIs.
  Renderer capabilities and game compatibility still depend on the device.
- Includes the prior SDL/GLFW backend isolation and renderer/native packaging work.
- **The reported Minecraft 26.x launch error on ARM32 and ARM64 is NOT confirmed fixed.**
  Exact error logs and device testing are still needed. Successful compilation, API checks
  and emulator tests do not establish real Minecraft gameplay compatibility.
- Java 25 remains unavailable for 32-bit x86. No universal recording or phone-performance
  guarantee is made.

## Verification and provenance
The release pipeline builds all five APK variants and native dependencies, checks APK
versions, ABI contents, signatures and SHA-256 hashes, and runs compatibility/API checks.
The Android API 29 x86_64 emulator test exercises the launcher/wardrobe UI and production
MP4 recording on SurfaceView and TextureView. It decodes recorded frames and checks that
an opaque magenta Android overlay is excluded. This is a synthetic capture test, not a
Helio A22 benchmark or a Minecraft gameplay test.

Attached files include `SHA256SUMS.txt`, `BUILD_INFO.json`, `NATIVE_BUILD_INFO.json`, the
rebuilt native AARs, and corresponding LTW/Rust core/RustGL source archives. Use these
provenance files to identify the exact source commit and build run.

If installation reports a signature conflict, do not uninstall just to bypass it without
backing up your worlds/settings. For a failed launch, share the exact Minecraft version
and the log from **Tools → Share logs** immediately after the failure.
