# Launcher media (development)

## Video wallpapers

Settings → custom background → Main menu → Add file accepts images or video. Select the
imported file just as with an image. MP4/M4V, WebM, MKV, 3GP and MOV are recognized;
**the container extension does not guarantee the device can decode its codec**.
Use H.264 MP4, preferably 720p. Limits: 256 MiB, 1920×1080 pixels per frame.
Videos loop silently on a TextureView behind the launcher. Pause/destroy releases the
MediaPlayer and Surface, including when launching Minecraft. Failed playback returns to
the plain background with an explanation. Reset or selecting an image stops the video.
Controls-editor and in-game custom backgrounds remain image-only.

## Skins and capes

Home → Tools → Skins & capes. Import up to 32 PNG files (4 MiB each); select/deselect
one skin and one cape, preview or delete them. Enter the exact Minecraft player name,
then export a ZIP through Android's document picker. Widths: powers of two 64–1024;
skins: square or 2:1 legacy; capes: 2:1.

This is a **local wardrobe + CustomSkinLoader pack exporter**, not a Microsoft skin
upload API. Install a compatible CSL mod yourself, then extract the pack beside that
instance's options.txt. The pack contains `CustomSkinLoader/GurkhaLocal/{skins,capes}`
and an `ExtraList/GurkhaLocal.json` Legacy provider. It does not overwrite an existing
CSL configuration. Repeated exports may require editing that provider's paths if its
previous configuration lacked a skin or cape. The ZIP includes instructions.
Model uses CSL's `auto` handling. No mod availability is promised for an arbitrary 26.x
build, and multiplayer visibility/official cape ownership is not changed.

Protocol reference (no upstream code copied):
https://github.com/xfl03/MCCustomSkinLoader/blob/15-develop/Common/src/main/java/customskinloader/loader/LegacyLoader.java
https://github.com/xfl03/MCCustomSkinLoader/blob/15-develop/Common/src/main/java/customskinloader/config/Config.java

## Gameplay recording

Game menu → Record gameplay / Stop recording. Recordings → select → Share/export or
Delete. Files stay in app external-files Movies/Recordings; sharing grants temporary
read permission through FileProvider. Export wanted recordings before uninstalling.

- **Silent** H.264 MP4, at most 854×480, target up to 15 fps, 1.5 Mbps when supported.
- Up to ten minutes, stops on activity pause or surface/capture/storage failure.
- PixelCopy receives **only MinecraftGLSurface's native Surface**, never a Window or
  MediaProjection display. Touch controls and launcher menus are separate views and
  cannot enter this capture path. Minecraft's own HUD/chat/menus remain visible.
- One small reusable bitmap and one copy in flight; GLES uploads it to a separate
  encoder surface/context. No changes to the game's GL context or framebuffer.
- Codec capability/alignment checks and encoder fallback; unsupported capture returns
  an error. A failed/empty output is deleted, not presented as a good MP4.
- Not universal device support. PixelCopy from a producer Surface, especially the
  TextureView path, depends on the Android implementation. There is no unsafe full-
  screen fallback. Capture can reduce game FPS, especially on Helio A22.

## Verification status / remaining device acceptance

Python integration checks and an executable Java policy test cover file recognition,
texture/name validation, output sizing, resource wiring and the surface-only boundary.
The native UI CI additionally opens the wardrobe and runs a debug-only, shell-permission
protected recording harness on SurfaceView and TextureView. It renders a synthetic
red/green game surface beneath an opaque magenta Android overlay, invokes the production
recorder, decodes three frames from the saved MP4 and rejects leaked overlay colors,
invalid dimensions/duration and encoder/capture failures. Results are saved in the UI
smoke artifact. This is **not** a real Minecraft, wallpaper playback, CSL integration or
phone performance test. Verify the CI result before treating that test as passed.
Required device checks on ARM32 and ARM64 (including GE8300):

1. MP4 and WebM playback, malformed/unsupported videos, image reset, rotation, home →
   game → home; ensure no decoder remains active during gameplay.
2. Skin and cape round-trip through export; invalid PNG/large dimensions rejected;
   check CSL loads the selected texture in a compatible game/mod version.
3. Record a moving scene with brightly colored touch controls and a visible launcher
   game menu; exported MP4 must show the game but **none of those Android overlays**.
   Test both SurfaceView and TextureView, stop, background, rotation/surface loss,
   unsupported encoder, low storage, repeated start/stop, share/delete and ten-minute
   limit. Verify timestamps, orientation, dimensions and decodability.
4. Record the exact 26.x build, launcher build, renderer, Java runtime and full error
   log. Existing ABI/API CI success has not reproduced or resolved the reported error.

RustGL branches checked: newest remains `70141c9602101ebdfb65296f7642bc69ac508ebb`,
already pinned in `tools/rustgl-lock.json`. No newer source was substituted.
