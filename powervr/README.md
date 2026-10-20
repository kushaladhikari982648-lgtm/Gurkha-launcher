# Proton Renderer in Gurkha Launcher

Built-in renderer: **Proton Renderer**, ID `opengles3_powervr`, built from the
upstream **PowerVR Unified r2** sources. Select it in a Minecraft version's renderer
settings and fully restart the game. No renderer-plugin APK or local ZIP import is
needed for the built-in entry.

The display name follows the upstream repository (`proton-renderer`, renamed from
`Powervr-the-poop`). The renderer ID, UUID, `libpowervr.so` name and
`ZalithLauncher/libs/powervr-release.aar` stay unchanged, so existing renderer
selections and the packaging checks keep working.

## Source and scope

Vendored build tooling/native port from:

- https://github.com/kushaladhikari982648-lgtm/proton-renderer
  (formerly https://github.com/kushaladhikari982648-lgtm/Powervr-the-poop)
- Development branch `arena/b3c523aa-powervr-the-poop`, pinned revision
  `8ad4affab77acae64df40de7aac49af6a9d02295` (**r2**; `main` only has a placeholder README).
- **Primary unified build**, not the older GL4ES baseline or dual-backend experiment.
- Krypton/NG-GL4ES `6e153a1622a8d9ef903f7975aafbef2453571bab` with an LTW-derived
  indirect-multidraw module compiled into the same library. See `backends.json`,
  `source-lock.json`, `README-upstream.md`, `docs/UNIFIED.md` and `docs/GE8300.md`.

### What changed in r2

`native/pv_context_cache.h` stores the `GL_EXT_multi_draw_indirect` capability and the
resolved host entry points inside each Krypton `glstate_s` (zero-initialized by
`NewGLState`, never shared between contexts) instead of re-querying them on every
draw. Context switches and recreated contexts therefore cannot reuse another
context's capabilities, and no process-global cache exists. `tests/native_multidraw.c`
asserts zero extra extension queries/symbol lookups over 10,000 subsequent draws and
checks two contexts with different capabilities. This is a call-count test, not an
FPS measurement; see `docs/GE8300.md` before judging performance.

This is a partial feature port, not a merger of all LTW functionality. There is
one native renderer, one GL namespace/state tracker, and no runtime backend switch.
The ported multidraw functions require the driver's real `GL_EXT_multi_draw_indirect`.
No performance improvement or compatibility with all Minecraft versions is claimed.
In particular this integration does not establish support for Minecraft 1.21.10.

## Launcher integration

`PowerVRRenderer.kt` selects `libpowervr.so`, explicitly sets `LIBGL_ES=3`, and
uses the system `libEGL.so` through the launcher's existing GL4ES context bridge.
This follows upstream's unified Zalith adapter. It does not use LTW's EGL wrappers,
load Krypton and LTW simultaneously, disable error reporting, or spoof a higher GL
version. The renderer has its own stable UUID, so existing renderer selections remain
unchanged. It is offered only on ARM, with GLES 3 and the native library present.

ABIs: `armeabi-v7a` (32-bit Android/JVM, including 32-bit GE8300 devices) and
`arm64-v8a`. x86 builds deliberately do not offer this renderer. Choose based on
the launcher process/JVM architecture, not just the CPU's hardware capabilities.
The existing Krypton cutoff and SDL3/LTW launch policy also apply to this derivative.

## Build and distribution

The Android workflow builds both ABIs once in the `PowerVRRenderer` job, using
NDK **28.2.13676358**, CMake **3.31.6**, API 21 and pinned recursive upstream
submodules. Both debug and release APK jobs download that same AAR before Gradle.
The NDK used to build the launcher itself is not changed.

```sh
python3 -m unittest discover -s powervr/tests -v
python3 powervr/tools/build_unified.py --ndk /path/to/android-ndk --abi arm64-v8a
python3 powervr/tools/build_unified.py --ndk /path/to/android-ndk --abi armeabi-v7a
python3 powervr/verify_native.py /path/to/android-ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf
python3 powervr/pack_aar.py
```

`ZalithLauncher/libs/powervr-release.aar` is built and committed back by the universal
push build, like RustGL. Its `.txt` sidecar records the source-tree hash, run URL,
ABIs and binary checksums. This checkout's checked-in AAR was repacked from the
upstream r2 unified CI builds of the pinned revision (see the sidecar). An APK
packaging check requires the PowerVR library in every ARM APK and forbids an
accidental x86 copy. ELF architecture/checksum, bridge exports and unbundled shared
dependencies are checked before packaging.

Local changes to upstream: Gurkha AAR packager and integration checks, this README,
NOTICE and source-lock. The native port and upstream build scripts are unchanged
from r2. Generated sources/build products in `.work/` and `artifacts/` are ignored
by Git.

## Licenses and corresponding source

See `NOTICE.txt`, `LICENSE`, and `licenses/`. The tooling MIT license does **not**
relicense Krypton, bundled dependencies, or the LGPL-3.0-only LTW-derived module.
Each upstream ABI ZIP contains full modified corresponding source (including
recursive submodules and original license files) archived before compilation.

- Debug runs: download **powervr-unified-sources** alongside the APK artifact.
- Tagged releases: both source-containing `powervr-unified-<abi>.zip` files are
  automatically attached alongside the APKs and included in release checksums.
- When mirroring/distributing an APK, preserve those source packages with it;
  Actions artifact retention is finite. Preserve LGPL replacement/relinking rights.
- The APK includes a PowerVR notice, license texts and per-ABI build metadata.

Upstream also publishes fresh per-ABI original-Zalith import ZIPs
(`powervr-zalith-r2-<abi>` artifacts) from every unified build; `tools/package_zalith.py`
here produces the same format with the **Proton Renderer (experimental)** name, and
its repackaging workflow requires an explicit native run ID so it cannot silently
ship r1 binaries.

## Validation boundary

Native/build/packaging tests and Android compilation do not prove visual correctness
on a PowerVR GE8300. Test a clean game instance without mods/shader packs, verify
GUI/text, terrain, entities and resizing, and retain the launcher log. Retest both
32-bit and 64-bit Android where available. If it fails, switch back to the previous
renderer; do not replace system GLES libraries or silently switch engines mid-process.
`docs/GE8300.md` describes a reproducible r1/r2 A/B procedure and diagnostic settings.
