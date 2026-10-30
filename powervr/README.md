# PowerVR Unified in Gurkha Launcher

Built-in renderer: **PowerVR Unified (Experimental)**, ID `opengles3_powervr`.
Select it in a Minecraft version's renderer settings and fully restart the game.
No renderer-plugin APK or local ZIP import is needed.

## Source and scope

Vendored build tooling/native port from:

- https://github.com/kushaladhikari982648-lgtm/Powervr-the-poop
- Development branch `arena/b3c523aa-powervr-the-poop`, pinned revision
  `1999cd5ee8245b20cacd322eeab6ee2d00a81110` (`main` only had a placeholder README).
- **Primary unified build**, not the older GL4ES baseline or dual-backend experiment.
- Krypton/NG-GL4ES `6e153a1622a8d9ef903f7975aafbef2453571bab` with an LTW-derived
  indirect-multidraw module compiled into the same library. See `backends.json`,
  `source-lock.json`, `README-upstream.md`, and `docs/UNIFIED.md`.

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
ABIs and binary checksums. An APK packaging check requires PowerVR in every ARM APK
and forbids an accidental x86 copy. ELF architecture/checksum, bridge exports and
unbundled shared dependencies are checked before packaging.

Local changes to upstream: Gurkha AAR packager and integration checks, this README,
NOTICE and source-lock. The native port and upstream build scripts are unchanged.
Generated sources/build products in `.work/` and `artifacts/` are ignored by Git.

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

## Validation boundary

Native/build/packaging tests and Android compilation do not prove visual correctness
on a PowerVR GE8300. Test a clean game instance without mods/shader packs, verify
GUI/text, terrain, entities and resizing, and retain the launcher log. Retest both
32-bit and 64-bit Android where available. If it fails, switch back to the previous
renderer; do not replace system GLES libraries or silently switch engines mid-process.
