# MojoLauncher review and built-in RustGL integration

## Sources inspected

- MojoLauncher/MojoLauncher, `v3_openjdk` at
  `1b8d90424c1cb791321d45f60ac62c54f4b013fa`. MJ is interpreted as MojoLauncher;
  the separately named BOMBA-NA/MJLauncher repository was empty when inspected.
- Mojo uses separate GLFW/SDL platform backends, Java selected from game metadata,
  and versioned Android LWJGL substitutions, including 3.4.3-r6. Its MojoSDL and
  mojoexec submodules are a different native/JNI contract, not drop-in Gurkha AARs.
- Gurkha branches inspected through GitHub: `main`, `gurkha-launcher`,
  `arena/1d667cb8-gurkha-launcher`, `arena/52ecd974-gurkha-launcher` and this branch.
  The 52ecd974 branch contains RustGL source, renderer registration and an older AAR.
- RustGL-for-Gurkha-launcher branches inspected: `main`, `arena/23f25781-...`,
  `arena/37ea4942-...`. The latter's revision
  `70141c9602101ebdfb65296f7642bc69ac508ebb` includes newer Android EGL normalization,
  capability-tiered GL claims, shader fixes and host GLES rendering tests. Its source,
  not the older launcher's binary, is vendored and rebuilt for every ABI.

## Concrete SDL startup fix

Gurkha's `libpojavexec` JNI_OnLoad previously looked up launcher-specific GLFW
methods and direct input buffers unconditionally in the game JVM. SDL builds use
another CallbackBridge and do not have that GLFW shim. Failed class/field lookup
could flow into unchecked JNI calls with null handles, aborting before rendering.

The launcher now sets `POJAV_WINDOW_BACKEND` from resolved game libraries, not a
version-name guess. The SDL path initializes only shared JVM hooks; the GLFW path
validates its methods and direct buffers before publishing them. Failed GLFW
initialization returns JNI_ERR rather than dereferencing invalid handles. This
follows Mojo's separation of window backends while retaining Gurkha's own surface,
input and Amethyst SDL integration. Tests run the actual helper in a host JVM with
`-Xcheck:jni`, including no GLFW class, missing fields and invalid buffers.

Java 25 selection, 32/64-bit ELF checks, JNI rather than Java-25 FFM dispatch,
GLFW/SDL library detection and default OpenGL backend selection are retained.
The bundled LWJGL is still the existing Android 3.4.1 build. Mojo's 3.4.3-r6 is
**not** silently substituted: changing jars without their matching Android native
libraries would introduce another ABI mismatch. API audits remain necessary but
are not game-launch proof.

## RustGL integration and optimization

- Optional **RustGL (experimental · GLES 3)** in the real renderer selector.
- Built from vendored Rust source for ARM32, ARM64, x86 and x86_64; no plugin needed.
- EGL and GL are routed to the same renderer, including SDL's EGL/OpenGL hints.
  Explicit RustGL selection is no longer silently replaced with LTW for SDL.
- GLES backend only; no Vulkan dependency, global LWJGL NoChecks, fixed GL 4.5
  override, or JAVA_TOOL_OPTIONS replacement. Existing LTW remains available.
- Driver entry-point dispatch uses thread-local hash maps instead of taking a
  global RwLock on every GL call. Context changes invalidate cached generations;
  null lookups are not cached and pointer types are size-checked even on hits.
- Retains upstream's bounded shader translation cache, lazy shader compile/link
  checks, format conversion and shader precision. No speculative precision cuts,
  forced CPU affinity, or unlimited caches for Helio A22.

CI builds all four renderer ABIs, tests the host JNI bridge, runs Rust unit tests
and real Mesa GLES rendering probes (full suite on 3.1/3.2; supported
paths plus explicit texture-buffer rejection on 3.0), checks APK native contents and runs existing
Minecraft API and Android UI checks. None substitutes for 26.x gameplay on a
PowerVR GE8300. Modern texture-buffer shaders require GLES 3.2 or GLES 3.1
with EXT/OES_texture_buffer; GLES 3.0 is not full modern-Minecraft support. Java 25 is still unavailable on 32-bit x86. RustGL is incomplete
and should be compared with LTW using a real device log; no FPS increase claimed.

This is beta.11 development work. No beta.11 release is requested or published by
these changes, and the published beta.10 APKs are not modified.
