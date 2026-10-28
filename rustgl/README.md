# RustGL renderer source (vendored)

This directory holds the source of the **RustGL** renderer that ships in Gurkha Launcher as
`ZalithLauncher/libs/rustgl-release.aar` (`librust_gl.so`): a desktop-OpenGL-to-GLES translation
layer written in Rust. Minecraft talks desktop GL (up to the 4.5 core surface) to `librust_gl.so`,
which rewrites shaders to GLSL ES and forwards to the device's OpenGL ES 3 driver.

## Provenance

- **Current source:** [RustGL-for-Gurkha-launcher](https://github.com/kushaladhikari982648-lgtm/RustGL-for-Gurkha-launcher),
  directory `rustgl/`, branch `arena/23f25781-rustgl-for-gurkha-launcher`, pinned at
  **`fdcca8d09ca2c810d43c38e68bd1fd80a27ac201`**. Its `main` branch still contains a separate
  SDL2 desktop demo, not the Android compatibility renderer.
- That snapshot is based on Gurkha commit `1726782a7989bdf9b5e50e2f3154ae2cfee06fdb`, so it retains
  the existing focus-independent renderer fixes: buffer textures, fog casts, legacy lightmaps,
  UBO updates, dispatch, and low-end GLES fallbacks.
- **Original upstream:** <https://github.com/whaltermc/rust-renderer>, revision
  `eb22559581cf400af29470205a9ebbff31e0db33`. `README-upstream.md` is its original README.
- [`source-lock.json`](source-lock.json) records the source revision and SHA-256 of every imported
  code/build/fixture file. `tests/test_rustgl_source.py` verifies them offline; integration README
  and provenance files are separate. Runtime code and the packer are copied byte-for-byte.

Original upstream publishes **no license file**. The source repository's notice says "used with the
author's permission"; this update does not establish a new license or independent permission grant.
See [`NOTICE-source.txt`](NOTICE-source.txt) (preserved source notice) and
[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) before redistribution. The notice's "synced
unmodified" paragraph describes its earlier baseline; the pinned snapshot includes the updates below.

## Updates from RustGL-for-Gurkha-launcher

1. **Driver lookup cache:** shared `RwLock` reads on cache hits instead of an exclusive mutex.
2. **Shader translation cache:** bounded translated-source caching keyed by a source fingerprint,
   length, ES/desktop target and buffer-texture capability. At most 128 entries / 2 MB of translated text.
3. **Compile/link scheduling:** drivers advertising `GL_KHR_parallel_shader_compile` no longer get
   immediate blocking status checks in the submit calls. Failure reporting remains on the status-query
   path; drivers without the extension keep the synchronous path.
4. **Base-vertex staging:** a bounded thread-local pool reuses CPU staging allocations for the
   emulated base-vertex draw path.
5. **Broader shader tests:** fourteen shader fixtures, adding representative Iris/OptiFine,
   Sodium-style and legacy shader dialects to the existing vanilla probes.

These are source-level throughput/latency changes, **not measured phone FPS improvements** and not
full shader-pack compatibility. The built-in renderer ID, saved selection, EGL library, default
GLES environment and launcher integration are unchanged; no plugin APK is required.

## Inherited Gurkha changes

The vendored copy is patched for low-end devices (MediaTek Helio A22/P22/P35-class SoCs with
IMG PowerVR GE8300 GPUs, 2–3 GB RAM, often 32-bit Android):

1. **Driver symbol cache** (`crates/gl-compat/src/lib.rs`): the per-call lookup table is a hash
   map instead of a linear scan under a mutex, so every forwarded GL call is cheaper on the
   slow Cortex-A53 cores these phones use.
2. **Base-vertex draws** (`crates/gl-compat/src/gl/v3_3.rs`): `glDrawElementsBaseVertex`,
   `glDrawRangeElementsBaseVertex` and `glDrawElementsInstancedBaseVertex` now emulate the
   vertex offset on the CPU (re-based scratch index buffer) when the driver has no base-vertex
   entry point. Before, the draw was dropped with `GL_INVALID_OPERATION`, which showed up as
   missing world geometry on drivers that report GLES 3.0/3.1.
3. **32-bit driver probing** (`crates/gl-compat/src/lib.rs`): the GLES/EGL library search also
   looks in `/system/lib/` and `/vendor/lib/`, which is where the driver lives on the many
   32-bit-only Android builds of these phones.

4. **Legacy dispatch**: resolves exported RustGL functions from this library's own handle
   before trying system EGL, including when the launcher uses `RTLD_LOCAL`. This prevents
   display lists, legacy state setters and matrix queries from resolving to ES1 stubs.
5. **Extension discovery**: returns stable extension-name pointers, matches NUL-terminated
   query names against the driver's non-NUL names, and uses valid/indexed GLES limit queries.
   Startup probing must not leave `GL_INVALID_ENUM` behind for the game's next operation.
6. **Buffer storage / UBO updates**: checks `GL_EXT_buffer_storage` before using the ES
   extension entry point. Ordinary storage falls back to ES buffers when necessary;
   persistent/coherent storage is rejected if unsupported. `GL_DYNAMIC_STORAGE_BIT` now
   permits subdata updates without allowing reallocation. Named storage shares the same
   implementation, and named storage/subdata preserve the caller's array-buffer binding.
7. **Fixed-function drawing**: samples texture unit 0 only when texturing is enabled on unit
   0, independent of the active server unit or whether an old texture remains bound.
   Float matrix queries are answered from the emulated matrix stack.
8. **Shader compatibility / diagnostics**: renames the ES-reserved `sample` identifier used
   by legacy post shaders. Unsupported stages fail compilation instead of becoming valid
   white/degenerate replacement shaders. Compile/link failures are logged once per object
   without needing `RENDERER_DEBUG`.

## Regression checks and device testing

The `RustGLChecks` CI job gates APK builds on:

- `renderer-core`, `gl-compat`, and **shader-translate** unit/fixture tests.
- Loading the actual Linux `librust_gl.so` under `RTLD_LOCAL` with a Mesa surfaceless GLES
  context, not a mock driver.
- Driver compilation of fourteen representative shader fixtures from the vanilla 1.12.2–1.21.10, modern buffer-texture/fog, and representative Iris/OptiFine/Sodium-style dialects.
- Pixel readbacks for legacy client-array quads with texturing enabled/disabled and a different
  active texture unit, plus modern UBO initial uploads and dynamic subdata updates (including
  named storage). These run with and without `GL_EXT_buffer_storage`, and on an ES 3.1 context to exercise the EXT texture-buffer entry points.

Host reproduction (Linux, Rust, Python 3, Mesa EGL/GLES development libraries installed):

```sh
cd rustgl
cargo test -p renderer-core -p gl-compat -p shader-translate -- --test-threads=1
cargo build -p gl-compat
EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 python3 tests/render_smoke.py
EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 MESA_EXTENSION_OVERRIDE=-GL_EXT_buffer_storage python3 tests/render_smoke.py
```

These tests do **not** establish full Minecraft or PowerVR driver compatibility. Retest
1.12.2, 1.20.1 (regression control), and 1.21.10 on the GE8300, first with vanilla/no shader
packs. Check the title screen, GUI/text, world terrain, entities, and framebuffer resize.
If blank output remains, collect the launcher/game log for each affected version, plus the
Android version and mod list. Look for `[RustRenderer]`, `[Shader]`, `[ShaderTranslate]`,
`[FFDraw]` and GL errors. `RENDERER_DEBUG=1` adds fixed-function draw-state diagnostics;
`RENDERER_TRACE_GL=1` is optional and can slow the game down.

## Building

`librust_gl.so` is built by GitHub Actions (`.github/workflows/android.yml`, step "Build RustGL
renderer") with `cargo-ndk` and packed into `ZalithLauncher/libs/rustgl-release.aar` before the
APK is assembled — see `ZalithLauncher/libs/rustgl-release.txt` for the exact toolchain.

Local build (Rust + Android NDK required):

```sh
cd rustgl
cargo test -p renderer-core -p gl-compat -p shader-translate -- --test-threads=1
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 build --release -p gl-compat
```

The outputs are `target/<triple>/release/librust_gl.so` (crate `gl-compat`, library name
`rust_gl`).

## Launcher integration

`com.movtery.zalithlauncher.renderer.renderers.RustGLRenderer` initializes it from this launcher:
renderer id `opengles3_rust` (OpenGL ES 3 context through the launcher's GL bridge), EGL and GL
entry points both taken from `librust_gl.so` (it re-exports the EGL surface the launcher's
`egl_loader` needs). Default environment:

| Variable | Value | Meaning |
|---|---|---|
| `LIBGL_ES` | `3` | create an OpenGL ES 3 context |
| `RENDERER_BACKEND` | `gles` | GLES 3 passthrough backend (the only one that draws) |
| `RENDERER_SPOOF_GL` | `1` | report OpenGL 4.5 core to the game and mods |
| `JAVA_TOOL_OPTIONS` | `-Dorg.lwjgl.util.NoChecks=true` | LWJGL pointer checks off (1.16 fog workaround) |

Debug switches (settable in the version's custom environment): `RENDERER_DEBUG=1` (additional
draw diagnostics), `RENDERER_TRACE_GL=1` (GL call tracing).

## Cross-version compatibility work (focus / buffer textures / legacy lighting)

This update does **not** make Java 8/17/21/25 interchangeable. Use the Java runtime required by
Minecraft. Rendering compatibility follows the Minecraft GL feature set, not the Java number.

- Desktop buffer samplers now select **ESSL 320** on GLES 3.2, or **ESSL 310 + EXT/OES_texture_buffer**
  on supported GLES 3.1 drivers. ES 3.0 without buffer textures is still unsupported for these shaders;
  no false capability or blank replacement shader is used. All translated desktop stages use the same
  context-selected dialect so buffer and non-buffer stages can link; ES 3.0 devices retain ESSL 300.
- Buffer textures use the supported core/EXT/OES native entry points. `glTextureBuffer`,
  `glTextureBufferRange`, and target-aware `glBindTextureUnit` cover modern DSA callers, with binding
  restoration. Mesa pixel tests sample integer buffer textures, update their backing buffer, and
  check ranged attachment and unbinding.
- User-function scalar float parameters get explicit integer conversions when their declaration is
  unambiguous (including modern `linear_fog_value(distance, 0, end)` calls). Overloads, integer
  parameters, output parameters, and texel-fetch indices are not blindly rewritten.
- Legacy fixed-function draws can use current texture/lightmap coordinates when client UV arrays
  are disabled, including the constant entity lightmap coordinate supplied by `glMultiTexCoord2f`.
  Pixel tests cover changing that coordinate; this is not complete fixed-function lighting or
  arbitrary per-vertex multitexture emulation.
- The launcher's GLFW bridge now reports actual Android focus/visibility and delivers changes on
  the game event thread. This addresses a possible inactive-window frame limiter affecting PowerVR
  Unified and other GLFW renderers. It does **not** establish that a specific phone's 30 FPS limit
  was caused by focus, or promise a frame rate. V-Sync and the game's FPS setting are not overridden.

**Still unconfirmed on device:** full Minecraft gameplay across these version families, especially
PowerVR GE8300. Minecraft versions routed through SDL still use the existing LTW policy: this patch
is not a RustGL SDL integration. Full Java-25-era/SDL support needs separate work and device logs.
Report the exact Minecraft version, Android version, renderer, and launcher/game log for any remaining
black screen. Compare vanilla with the same settings, and test background/resume as well as gameplay.
