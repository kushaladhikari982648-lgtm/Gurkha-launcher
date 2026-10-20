# One renderer: PowerVR unified (partial integration)

This build produces **one `libpowervr.so`**, with Krypton as the GL implementation
and an LTW-derived multi-draw-indirect feature port compiled directly into its
existing drawing translation unit. There is no LTW backend loader, selector or
second renderer context in this build.

## Actual integration scope

- Base: NG-GL4ES / Krypton commit pinned in `backends.json`.
- Port: LTW's `glMultiDrawArraysIndirect` and `glMultiDrawElementsIndirect`
  native EXT forwarding from the pinned MojoLauncher/LTW `multidraw.c`.
- Adapted to Krypton's GLES resolver, VAO/element-buffer binding, texture and
  shader realization, pending-list flushing, and GL error handling.
- Added current-context extension checking, missing-entry-point handling,
  indirect-buffer validation, stride/alignment/overflow checks and host C tests.
- Unsupported indirect multidraw returns an error rather than silently doing
  nothing. This port does not advertise new extensions or spoof a GL version.

**This is a first feature-level integration, not all of LTW merged into Krypton.**
LTW's shader converter, EGL manager, blending state and framebuffer workarounds
are not transplanted. They need individual state/lifetime audits and device tests.
The earlier dual-backend build remains a separate experiment, not this renderer.

## Build

Use Python 3, Git, CMake 3.31.6 and Android NDK 28.2.13676358:

```sh
python3 -m unittest discover -s tests -v
python3 tools/build_unified.py --ndk /path/to/ndk --abi arm64-v8a
```

Output: `artifacts/arm64-v8a/powervr-unified.zip`, containing exactly one renderer
library, build metadata, applicable license texts and full modified corresponding
source (including submodules). Rebuild that source directly with the same CMake
NDK arguments and target `ng_gl4es`; its output name is `libpowervr.so`.

The source archive includes upstream notices/licenses. Original Krypton code
retains its licenses; the modified LTW-derived module is LGPL-3.0-only (including
its adaptations). Do not apply the root tooling MIT license to this module.
Preserve notices and modified corresponding source, permit library replacement,
and meet LGPL/GPL obligations when redistributing a combined work. No LTW binary
or plugin signing key is incorporated.

## Status and limits

Local native mock tests cover dispatch, rejection paths, extension matching,
list handling and argument arithmetic. The modified Krypton drawing translation
unit passes a host syntax check against upstream headers (with upstream warnings).
Neither is proof of Android loadability or visual correctness.

This still needs a launcher-specific adapter and on-device testing. It is not an
installable APK, a universal launcher import, or guaranteed support for every
Minecraft version, mod or shader pack. The port requires the real
`GL_EXT_multi_draw_indirect` driver extension to execute these two functions.
Driver buffer range/mapping and draw-mode checks remain driver responsibilities.
There is no measured PowerVR speedup. Do not run the old backend-selector tool
for this build: it always uses the single integrated Krypton state implementation.

## r2: avoid repeated CPU-side capability discovery

The integrated indirect multidraw path now stores extension availability and host
function pointers in each Krypton `glstate_s`. State is zero-initialized by
`NewGLState`, not copied from a shared context, and discarded on state destruction.
Thus context switches and context recreation do not reuse a process-global cache.
The dynamic indirect-buffer binding validation is retained for correctness.

A host regression test executes 10,000 subsequent draws and asserts **zero**
additional extension-count queries, extension-string reads or symbol resolutions.
It also checks two contexts with different capabilities. This is a call-count
test, not a frame-time benchmark or evidence of an FPS improvement on GE8300.
Vanilla Minecraft may not use this path; GPU fill rate, shader compilation,
regular draw submission, chunk builds and JVM pauses remain unprofiled.

Each unified CI build now produces a fresh per-ABI original-Zalith import ZIP
(`powervr-zalith-r2-<abi>` artifact). The separate repackaging workflow requires
an explicit native run ID, so it cannot silently ship the old r1 native binaries.

See [GE8300 comparison procedure](GE8300.md) before judging performance.
