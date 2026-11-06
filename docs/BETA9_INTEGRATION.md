# beta.9 branch integration

Reviewed `arena/52ecd974-gurkha-launcher` (1323936), `gurkha-launcher` (253b64d),
and the active compatibility branch. Selected UI, launcher-core, low-end resource policy and GLFW
lifecycle code from 253b64d; retained the current Java/ABI/LWJGL compatibility changes.
Experimental RustGL/PowerVR GPU backends and old release workflows were not blindly merged.

Latest LTW revision is pinned in tools/renderer-lock.json. CI compiles it from source for all ABIs;
the successful preview job also synchronizes the built AAR and provenance to this same branch.
Rust is used for launcher file hashing and atomic file writes, not a claim of faster gameplay.
The native core is independent of the renderer and preserves the Java fallback.

SDL fixes address initialization and surface-notification paths. They are candidates for the reported
26.x problem, not a diagnosis without a device log. API linkage checks do not validate SDL/native
behavior or GPU rendering. Request the exact game version, log and Android ABI after a phone test.

Source/attribution: launcher integration from the same GPL-3.0 project at 253b64d;
launcher-core GPL-3.0-or-later, jni/sha2 upstream MIT/Apache-2.0;
LTW LGPL-3.0, pinned complete corresponding source included with the preview.
