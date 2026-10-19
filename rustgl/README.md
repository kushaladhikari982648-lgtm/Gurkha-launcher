# RustGL renderer source (vendored)

This directory holds the source of the **RustGL** renderer that ships in Gurkha Launcher as
`ZalithLauncher/libs/rustgl-release.aar` (`librust_gl.so`): a desktop-OpenGL-to-GLES translation
layer written in Rust. Minecraft talks desktop GL (up to the 4.5 core surface) to `librust_gl.so`,
which rewrites shaders to GLSL ES and forwards to the device's OpenGL ES 3 driver.

## Provenance

- Upstream: <https://github.com/whaltermc/rust-renderer> (a ZalithLauncher 2 renderer plugin,
  `dev.rustrenderer.plugin`, "RustGL")
- Vendored revision: `eb22559` ("Merge pull request #2 ... fix-mc-rendering-gl45")
- `README-upstream.md` is upstream's own README at that revision.

Upstream publishes **no license file**. Until the upstream author adds one, treat this code as
"used with the author's permission" rather than GPL code — see `THIRD_PARTY_NOTICES.md` at the
repository root. Everything else in Gurkha Launcher is GPL-3.0.

## Local changes

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

## Building

`librust_gl.so` is built by GitHub Actions (`.github/workflows/android.yml`, step "Build RustGL
renderer") with `cargo-ndk` and packed into `ZalithLauncher/libs/rustgl-release.aar` before the
APK is assembled — see `ZalithLauncher/libs/rustgl-release.txt` for the exact toolchain.

Local build (Rust + Android NDK required):

```sh
cd rustgl
cargo test -p renderer-core          # host tests
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

Debug switches (settable in the version's custom environment): `RENDERER_DEBUG=1` (shader
compile diagnostics), `RENDERER_TRACE_GL=1` (GL call tracing).
