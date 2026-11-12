# Minecraft 26.x: concrete bridge and native-bundle migration plan

## Scope and status

**Implementation update:** see [26X_HARDENING.md](26X_HARDENING.md) for the implemented
subset, test evidence and remaining work. The rest of this document records the
original proposal; it must not be read as a claim that every phase is complete.

This is a source-audited implementation plan for Gurkha's Zalith 1 fork at
`867df9c` (beta.11 release synchronization). **The code examples below are proposed
edits, not patches already applied to the runtime.** No released APK is changed by
this document. They require integration, negative tests and real game/device testing.

The user's report that Mojo/MJ and Zalith 2 work on the same device is a useful
baseline, but we still need the exact version, renderer and first failure from this
fork to identify the actual crash. SIGSEGV and UnsatisfiedLinkError are symptoms,
not interchangeable diagnoses. Do not replace every library before identifying the
failing stage.

**Recommended strategy:** repair initialization/error propagation and make library
selection deterministic first; establish LTW + OpenGL as the baseline; migrate one
complete Android LWJGL bundle at a time. Do not replace the whole launcher UI or
blindly merge another launcher's native bridge.

## 1. What the source actually establishes

This fork already has:

- SDL/GLFW detection and isolated modern native directories in `SdlSupport.kt`.
- LTW routing, `libltw_sdl_egl.so`, built-in RustGL and a GLFW/SDL JNI guard.
- Java runtime/ELF architecture checks and a Java-25 JNI-compatible LWJGL core jar.
- Native build, Java API and synthetic recording/UI tests.

Those tests do **not** prove that Minecraft creates a working GL context or renders
a world on a phone. Another general API audit is not a substitute for a GL/game test.

### Concrete issues visible in the current fork

Paths below are relative to `ZalithLauncher/src/main/` unless otherwise indicated.

| Location | Observed behavior | Required change |
|---|---|---|
| `jni/ctxbridges/egl_loader.c:dlsym_EGL` | A failed explicit EGL provider silently falls back to `libEGL.so`; function pointers are not comprehensively checked. | Explicit renderer provider failure must stop launch; validate required EGL dispatch before calling it. |
| `jni/egl_bridge.c:pojavInitOpenGL/pojavInit` | Calls `br_init` without checking selection; ignores failed bridge initialization and returns success from `pojavInit`. Also unconditionally attempts Vulkan loading in the GL initialization path. | Validate backend selection, return failure to GLFW, and load Vulkan only for a path that actually needs it. |
| `jni/ctxbridges/gl_bridge.c:gl_init_context` | Always chooses `EGL_OPENGL_ES2_BIT`; reads `LIBGL_ES` through `strtol(getenv(...))` without a null check; continues after bind failure. | Select config according to the actual host API/version, validate inputs and stop on each failed EGL operation. |
| `jni/ctxbridges/gl_bridge.c:gl_swap_buffers` | Dereferences `currentBundle` without checking that make-current succeeded. Surface-change fields are shared without an explicit synchronized ownership protocol. | Do not swap with no current context; implement a render-thread surface handoff. |
| `jni/egl_bridge.c:setupBridgeWindow/releaseBridgeWindow` | Native window references and release/reset are not represented by one explicit ownership protocol. | Acquire/release each owned reference exactly once, including replacement, detach and repeated callbacks. |
| `../jre_lwjgl3glfw/.../GLFW.java:mglfwCreateWindow` | Continues constructing/registering window state after native context creation returns zero. | Return zero immediately and report context creation failure. Do not advertise a window that was not created. |
| `java/.../launch/SdlSupport.kt` | All detected LWJGL 3.4+ GLFW versions and SDL versions use the pinned 3.4.1 component. | Replace the open-ended version rule with a tested requested-version → Android-bundle mapping. |
| `java/net/kdt/pojavlaunch/utils/JREUtils.java` | Built-in GL/EGL names are mostly bare sonames; renderer load retries a search path; runtime initialization preloads many JRE libraries globally. | Resolve renderer paths once, fail on missing providers, and audit duplicate sonames/dependency closure. |
| `JREUtils.launchJavaVM` / `LaunchArgs.getMinecraftJVMArgs` | Launcher properties are assembled before the later manifest JVM argument list. A later duplicate `-D` can override the launcher's value. | Assemble the complete VM argument section first; enforce protected native properties last, before the main class. |
| `NativesExtractor.java` | A filename blacklist prevents downloaded AARs from overriding packaged libraries. | Do not merely remove this blacklist. Extract approved modern bundles into their own ABI/version/hash directory with a dedicated manifest. |

These are code-level hazards. **They are not proof that any one is the user's
specific 26.x failure.** In particular, some Android EGL drivers permit an ES3
context with an ES2-advertising config; the current choice is not guaranteed to
fail everywhere, but it is not a reliable modern config-selection policy.

### Upstream comparison, pinned for reproducibility

- MojoLauncher `1b8d90424c1cb791321d45f60ac62c54f4b013fa` uses versioned
  `substitutions.json` entries, including Android-built LWJGL 3.4.1-r6 and 3.4.3-r6
  artifacts. Its download classifiers may say `natives-linux-*`; trust the pinned
  Android build provenance, not the classifier label alone.
- Its GLFW, MojoSDL and mojoexec are coordinated submodules, not independent
  drop-in `.so` replacements. `libGLMojo.so` is a loader alias intercepted by its
  native hook, not a file this fork can simply select.
- MojoSDL's EGL code acquires the renderer handle from mojoexec and supports a
  renderer-specific forced GLES context. That consistency is the design to port.
- Zalith 2 `4607f153fcce149a8e0ad39dc93fc447589d9f17` still contains Pojav-derived
  EGL/GL bridge code. It selects separate LWJGL components and routes MobileGlues
  through renderer configuration, with an optional SimpleFPEWrapper layer. It is
  not evidence that a blanket EGL bridge replacement alone is the fix.
- Do not copy Zalith 2's fixed surface-destruction delay as a synchronization
  solution. Establish ownership and render-thread ordering instead.

Pinned references are listed at the end.

## 2. Resolve a launch plan from metadata, not the name "26.x"

Read the **merged/inherited** game manifest, its Java requirement, its LWJGL core
coordinate and whether `org.lwjgl:lwjgl-sdl` is present. A mod loader's own release
date is not a rendering compatibility contract.

The existing repository fixtures establish these requested combinations:

| Fixture | Required Java | Requested LWJGL | Window backend |
|---|---:|---|---|
| 1.21.11 | 21 | 3.3.3 | GLFW |
| 26.1 / 26.2 | 25 | 3.4.1 | GLFW |
| 26.3 / 26.4-snapshot-7 | 25 | 3.4.3 | SDL |

Do not extend this table to unseen releases without checking their manifests.
A requested game version and a substituted Android bundle version are separate
fields. A deliberately tested substitution is valid; an implicit "any future
3.x/4.x uses our 3.4.1" fallback is not.

Proposed model, in a new `launch/NativeLaunchPlan.kt`:

```kotlin
enum class WindowBackend { GLFW, SDL }
enum class HostContextApi { GLES, DESKTOP_EGL, OSMESA }

data class NativeLaunchPlan(
    val requestedLwjgl: String,
    val bundleId: String,          // immutable version + Android patch-set identifier
    val bundleDigest: String,
    val processAbi: String,        // running APK/process, NOT merely Build.SUPPORTED_ABIS[0]
    val windowBackend: WindowBackend,
    val hostContextApi: HostContextApi,
    val nativesDir: File,
    val rendererGl: File,
    val rendererEgl: File?,
    val sdlLibrary: File?,
    val javaMajor: Int
)
```

Resolve this plan once before loading game natives and reuse it in `LaunchArgs`,
`JREUtils`, `MinecraftGLSurface`, renderer policy and JNI initialization. Reject
unsupported tuples with an actionable error rather than choosing the oldest bundle.
Keep legacy profiles isolated and usable.

## 3. Native bridge edits: context ownership and real error propagation

### 3.1 Make the explicit EGL provider fail closed

Change `dlsym_EGL()` in both `.c` and `.h` to return `bool`. Keep the selected
provider handle alive for the bridge's lifetime. Do not load system EGL as a
replacement for a missing LTW/MobileGlues/RustGL provider.

Proposed control flow (integrate with the existing pointer table and logging):

```c
const char *provider = getenv("POJAVEXEC_EGL");
const bool explicit_provider = provider && provider[0];
if (!explicit_provider) provider = "libEGL.so";

void *handle = dlopen(provider, RTLD_NOW | RTLD_LOCAL);
if (!handle) {
    fprintf(stderr, "EGL provider %s: %s\n", provider, dlerror());
    return false;                 /* no fallback when a renderer was requested */
}

/* Resolve into a temporary table, validate it, then publish the complete table.
 * Preserve the renderer's eglGetProcAddress dispatch. Some wrappers export only
 * a subset via dlsym and forward the rest through eglGetProcAddress.
 */
if (!resolve_required_egl_table(handle, &candidate)) {
    /* Log the provider and missing function; do not publish partial pointers. */
    dlclose(handle);
    return false;
}
```

`resolve_required_egl_table` is a new helper to implement, not an existing API.
Check every function the bridge calls: display/init/config/bind, context/surface
creation, make-current, query, swap/interval, error, destruction and thread release.
Optional extension entry points must remain optional and feature-gated. Distinguish
`EGL_NOT_INITIALIZED` from `EGL_BAD_CONFIG`, `EGL_BAD_MATCH` and `EGL_CONTEXT_LOST`.

If `LIBGL_GLES` selects ANGLE, resolve its matched EGL/GLES pair explicitly; an
ANGLE failure must not silently become a system-EGL context plus ANGLE GL dispatch.

Change `gl_init()` to start with `if (!dlsym_EGL()) return false;`. In
`pojavInitOpenGL()`, validate the renderer/backend table and return a success value.
In `pojavInit()`, check the native surface and dimensions, balance any temporary
window reference on every exit, and return that success value. The existing
`GLFW.glfwInit()` already consumes `pojavInit()`'s integer return value.

Do not unconditionally call `load_vulkan()` for LTW or MobileGlues. Keep explicit
Vulkan loading for Zink/direct Vulkan paths that actually require it.

### 3.2 Request the correct HOST context

Desktop OpenGL is what Minecraft sees; GLES may be what a translation renderer
uses underneath. These are different contracts.

For LTW/MobileGlues-on-GLES, select an ES3-capable host config, bind GLES, then create
through **that renderer's EGL dispatch**, not by bypassing it with system EGL:

```c
/* Proposed policy fragment; includes/dispatch are the existing bridge's. */
const EGLint renderable = host_es_major >= 3
    ? EGL_OPENGL_ES3_BIT_KHR
    : (host_es_major == 2 ? EGL_OPENGL_ES2_BIT : EGL_OPENGL_ES_BIT);
const EGLint attributes[] = {
    EGL_RENDERABLE_TYPE, renderable,
    EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
    EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
    EGL_ALPHA_SIZE, 8, EGL_DEPTH_SIZE, 24, EGL_NONE
};
EGLint count = 0;
if (!eglBindAPI_p(EGL_OPENGL_ES_API)) return NULL;
if (!eglChooseConfig_p(display, attributes, &config, 1, &count) || count < 1)
    return NULL;
const EGLint context_attributes[] = {
    EGL_CONTEXT_CLIENT_VERSION, host_es_major, EGL_NONE
};
EGLContext context = eglCreateContext_p(display, config, shared_context,
                                      context_attributes);
if (context == EGL_NO_CONTEXT) return NULL;
```

Parse `LIBGL_ES` safely with a default, full-string validation and a supported
range, or preferably pass an enum/integer from the resolved plan. Check allocation,
`eglGetConfigAttrib`, surface creation and make-current results too. Log the EGL
error immediately at the failed call; later calls can overwrite the error.

For a genuine desktop-EGL Mesa provider, use `EGL_OPENGL_API`, `EGL_OPENGL_BIT` and
its supported desktop context attributes instead. Do not request desktop GL from
Android's system EGL and expect a translation layer to appear automatically.

Do not silently downgrade a GLES3 renderer to an ES2 **context**. If a specific
older driver needs an ES2-advertising config for an ES3 context, make that an
explicit tested compatibility path and verify the resulting host context. Do not
assume `EGL_CONTEXT_CLIENT_VERSION=3` guarantees every ES3.1/3.2 capability.

### 3.3 Keep one context owner for each backend

- **GLFW path:** launcher bridge creates/destroys contexts and surfaces; the shim
  exposes GLFW lifecycle/window state. After native context creation returns zero,
  `mglfwCreateWindow` must return zero immediately, without adding a window to its map.
- **SDL path:** SDL owns context/window lifecycle. Do not call the legacy GLFW
  context creation/swap/termination path for the same window. Retain
  `runtime_bridge.h`'s SDL JNI guard. Official GLFW *types* may remain for mod
  linkage; the private launcher GLFW shim must not initialize on this path.
- An EGL context, display, surface and GL function provider must belong to the
  same renderer stack. Do not create a system context and then dispatch through
  an LTW wrapper that expects its own context bookkeeping.
- Keep `libltw_sdl_egl.so` for LTW with this fork's SDL until a coordinated MojoSDL
  port replaces it. Do not point SDL directly at a partial EGL-export library and
  assume `dlsym()` can find its complete EGL API.
- Audit the LTW SDL front's aborting initialization, incomplete-function behavior
  and error forwarding. An `eglCreateContext` wrapper should not consume and lose
  the EGL error that SDL will query next; preserve/forward it per thread if logging
  requires reading it. Do not mechanically remove context attributes or color-space
  requests for every renderer.

### 3.4 Surface lifetime and threading are a separate patch

Replace unsynchronized `state/newNativeSurface` publication with a guarded pending
surface plus generation number. The UI callback obtains an owned native-window
reference; the render thread atomically takes ownership of the pending reference.
It unbinds/destroys the previous EGL surface, creates the new surface with the same
provider/config, makes it current, updates size and then releases old ownership.

Each slot must have a documented reference owner: Java-to-native published window,
pending replacement, and active render window. Repeated destroy/detach must be
idempotent. `ANativeWindow_fromSurface` already acquires a reference; count it.
Never leave a released window in `pojav_environ->pojavWindow`.

Do not hold the publication mutex across EGL/driver calls. Perform EGL work on the
thread owning the context. On `EGL_BAD_SURFACE`, use a supported pbuffer/detached
state and wait for a replacement; on `EGL_CONTEXT_LOST`, rebuild resources through
the appropriate backend or abort clearly—do not pretend a surface-only replacement
recovers GL objects. Do not use a sleep or invalid-width heuristic as synchronization.

Guard swaps and queries when no valid current bundle exists. A failed make-current
must not be followed by `GL.createCapabilities()` or GL calls. Propagate a bridge
error to the shim/game thread instead of hiding repeated failures.

On ARM32/64, use `uintptr_t`/`intptr_t` for pointer conversion and `jlong` for Java
handles. Never store a Java long handle in C `long`. Never reuse a `JNIEnv*` across
threads or between ART and the embedded HotSpot VM.

## 4. LWJGL/native mapping: one approved bundle, no search-path roulette

### 4.1 Bundle identity

Replace the open-ended `usesLwjgl341WithGlfw()` substitution with a manifest-backed
resolver. A bundle lock must list:

- requested LWJGL versions explicitly supported by this substitution;
- Android Java module revisions, hashes and patch set;
- window backend and matching GLFW/SDL bridge revision;
- each ABI's native filenames, SHA-256, ELF class/machine, DT_SONAME, DT_NEEDED;
- minimum Android API and compatible runtime/renderer combinations;
- which optional modules are absent (e.g. jemalloc) and the supported alternative.

Migrate to Mojo's 3.4.3-r6 **as a whole tested bundle** for appropriate requests if
needed: core, all required Java modules, corresponding JNI natives, and compatible
SDL/GLFW glue. Do not pair a new `lwjgl.jar` with old `liblwjgl.so`. Preserve the
working older bundle for old profiles. A Java symbol-reference audit does not
verify JNI entry points, FFM behavior, Bionic compatibility or driver behavior.

The current modern directory already isolates ABI. Extend its identity to include
bundle hash; stage and verify all files before publishing a completed directory:

```text
components/lwjgl/<bundle-id>/<bundle-hash>/jars/
components/lwjgl/<bundle-id>/<bundle-hash>/natives/<process-abi>/
```

Do not overlay files from several revisions in the same directory. Do not mutate
a directory while a game process is using it. Do not let a downloaded desktop
native JAR bypass the approved resolver. If native extraction is enabled through
`org.lwjgl.system.SharedLibraryExtractPath`, give it the same isolated identity and
ensure only approved Android artifacts can supply those files.

`NativeLibraryInfo.matches()` is useful but only validates basic ELF architecture.
ARM64 Linux/glibc and Android/Bionic can share that ELF machine/class; inspect build
provenance, interpreter/dependencies and exported JNI symbols as well.

### 4.2 Know what each library property selects

| Setting/file | Purpose |
|---|---|
| `org.lwjgl.librarypath` | Selected LWJGL native bundle directory, not the GL driver itself. |
| `org.lwjgl.libname` | Core library override in compatible LWJGL builds; verify support in the selected core. |
| `liblwjgl_opengl.so` | LWJGL JNI helper; must match the Java OpenGL module. It is **not** the renderer. |
| `org.lwjgl.opengl.libname` | Desktop GL function provider: LTW/MobileGlues/Mesa/RustGL as explicitly selected. |
| `POJAVEXEC_EGL` | EGL provider used by this fork's legacy bridge. |
| `SDL_EGL_LIBRARY` | Complete EGL provider/front used by this fork's SDL. |
| `SDL_OPENGL_LIBRARY` | GL provider expected by the selected SDL port. Mojo/Zalith ports may interpret this differently. |
| `org.lwjgl.freetype.libname`, `shaderc.libname`, `spvc.libname`, `sdl.libname` | The respective external helper library, using properties actually supported by the selected modules. |

Do not invent a `liblwjgl_sdl.so` mapping: the current shipped bundle has no such
file. Inspect how its SDL binding and `libSDL3.so` are built. Never rename a library
to satisfy a guessed filename without matching its symbols and dependencies.

Use absolute verified paths for providers/helpers. Keep modules not requiring a
native helper as Java-only; do not manufacture `.so` mappings for every jar.

### 4.3 Enforce properties AFTER merging VM args, BEFORE the main class

Refactor `LaunchArgs.getAllArgs()` into an explicit VM-arguments section and a
main-class/game-arguments section. Combine defaults, user JVM args, loader/manifest
JVM args and version args first. Then remove duplicate protected properties and
append the resolved values. For example:

```java
// Proposed helper: call only on VM args, never on Minecraft's command-line args.
static void enforceProperties(List<String> vmArgs, Map<String, String> locked) {
    for (Map.Entry<String, String> entry : locked.entrySet()) {
        String key = "-D" + entry.getKey();
        vmArgs.removeIf(arg -> arg.equals(key) || arg.startsWith(key + "="));
        vmArgs.add(key + "=" + entry.getValue());
    }
}
```

Then assemble `vmArgs + mainClass + gameArgs`. Adding a property after `mainClass`
does not configure the JVM. Do not globally strip unrelated mod/JNA properties.
Decide `java.library.path` explicitly: include only validated per-instance native
extensions plus approved directories, and reject collisions with owned bundle
libraries. Resolve `${natives_directory}` consistently rather than allowing it to
restore the legacy app-wide directory later in argument assembly.

Likewise, build the final environment once. Normalize/remove conflicting inherited
`SDL_EGL_LIBRARY`, `SDL_OPENGL_LIBRARY`, `POJAVEXEC_EGL`, `LIBGL_EGL` and backend
variables, then install the resolved values before any renderer/native load.
Preserve an intentional developer override only as an explicit unsafe/debug mode,
not a silent setting that invalidates the bundle contract.

### 4.4 Native loader and soname closure

- Resolve built-in renderer files under `DIR_NATIVE_LIB`; resolve plugin providers
  under their verified plugin directory. If the selected file cannot load, report
  the exact path and `dlerror()`. Do not retry a same-named file in arbitrary folders.
- Audit `JREUtils.initJavaRuntime()`'s bulk global preload. JRE FreeType and LWJGL
  FreeType, old/new LWJGL helpers, shaderc and C++ runtimes can collide. Do not remove
  required JRE dependencies indiscriminately; build an explicit JRE preload list
  and test AWT/font paths too.
- Use `RTLD_NOW` for controlled provider loads so missing relocations fail early;
  use `RTLD_LOCAL` where supported by the dependency contract. Do not blindly change
  every JVM load from GLOBAL to LOCAL or use `RTLD_DEEPBIND` as an Android workaround.
- Absolute `dlopen` paths alone do not fix `DT_NEEDED`/soname/global symbol conflicts.
  If two incompatible sonames genuinely coexist, rebuild with coordinated unique
  SONAME/DT_NEEDED or use a proven Android namespace design. Renaming files alone is
  not enough. Start a fresh game process when changing native bundle/provider.
- Keep the Android linker-namespace hook in the correct classloader/native context;
  log which provider it actually resolves. Do not assume changing `LD_LIBRARY_PATH`
  at runtime reconfigures all Android linker namespaces.
- Do not validate a HotSpot LWJGL JNI library by `System.load()` in ART. Static ELF
  checks may run in the launcher; dynamic JNI validation must use the actual game
  runtime/loader. Both VMs can coexist in a process and have different JNI state.

For Java 25, retain the fork's JNI-compatible core handling until the replacement
Android bundle is proven correct. Do not reintroduce the desktop Java-25 FFM layer
merely by swapping jars. Do not disable LWJGL checks to mask invalid entry points.

## 5. Renderer routing

### Baseline: LTW + game OpenGL

For the first reproducible test, explicitly choose LTW and the game's OpenGL path.
Use the selected LTW GL library with its EGL dispatch; for SDL retain the LTW SDL
EGL front. Ensure no stale `preferredGraphicsBackend:vulkan` bypasses this path.

The existing `GraphicsBackend.kt` defaults absent/default settings to OpenGL but
preserves an explicit Vulkan setting. That is user-friendly generally, but it must
be recorded in diagnostics. A controlled baseline test should select OpenGL in the
profile/UI or use the argument only for game versions verified to support it.
Do not infer that argument support solely from an arbitrary mod's Vulkan dependency.

An LTW result still depends on the driver's capabilities and the specific shaders/
features the game uses. GLES3 detection alone is an eligibility check, not proof of
full modern desktop OpenGL support.

### Add MobileGlues as a separate, pinned renderer

Before registering it, obtain a reproducible Android build/source revision and
license/provenance; inspect ABIs, dependencies and EGL exports. Do not copy a binary
from another APK and assume it supports ARM32 and ARM64.

Proposed `MobileGluesRenderer.kt` contract:

```text
id                  = opengles3_mobileglues  (while the legacy prefix routing exists)
GL provider         = <verified renderer dir>/libmobileglues.so
EGL provider        = the same renderer's verified EGL dispatch
host API/version    = GLES / 3 (subject to the pinned build's documented contract)
LIBGL_ES            = 3
LIBGL_EGL           = <verified MobileGlues provider path>
POJAVEXEC_EGL       = <verified MobileGlues provider path>
```

`JREUtils` must resolve `getRendererLibrary()`/`getRendererEGL()` to those paths.
Register the new stable renderer identifier and AAR in `Renderers.kt`/Gradle only
after validation. Keep the current SDL allowlist closed until MobileGlues' SDL path
has a complete tested EGL front or matched SDL patch. Do not just add its ID to
`RendererPolicy` and assume the SDL path works.

Zalith 2 can put SimpleFPEWrapper in front of its renderer. If porting that mode,
port the whole provider chain (GL dispatch, EGL dispatch, dependency and configuration
mapping), not just one environment variable. Begin with the simplest supported
MobileGlues configuration; do not force ANGLE/Vulkan on the PowerVR test device.

### Mesa and RustGL

- Mesa is not one backend. Zink is desktop GL over Vulkan; it needs a functioning
  Vulkan device/driver and tested synchronization/extensions. Software Mesa can be
  a diagnostic option, not an assumed playable Helio A22 solution.
- A desktop-EGL Mesa route and an OSMesa route have different context handles and
  creation APIs. Do not feed an OSMesa context to EGL/SDL.
- Keep Freedreno removed; it is not the PowerVR GE8300 fix.
- Keep RustGL opt-in/experimental until the same game tests pass. Updating RustGL
  cannot fix mismatched LWJGL JNI libraries or a competing context owner.
- Do not force GL/GLSL version strings or advertise extensions whose functions and
  behavior are unavailable. A higher reported version does not implement DSA,
  texture buffers, fences, persistent mapping or the required shader semantics.

## 6. Diagnostics and acceptance gates

Log the following, without credentials/access tokens:

1. exact game + loader versions, resolved Java major/runtime ABI, running process ABI;
2. window backend, requested LWJGL version, selected bundle ID/hash;
3. absolute GL/EGL/SDL/helper paths, their build IDs and loaded paths (`dladdr` /
   `dl_iterate_phdr`, or process maps when permitted);
4. each EGL stage, requested attributes, chosen config and immediate failure code;
5. after a valid current context: GL vendor/renderer/version/GLSL version and
   required capabilities; never call GL queries before make-current succeeds;
6. unredacted native symbol names/PC offsets for crashes, but redact account tokens.

For native artifacts, use the NDK tools (replace paths with the real built files):

```sh
llvm-readelf -h -d -Ws path/to/liblwjgl.so
llvm-readelf -h -d -Ws path/to/liblwjgl_opengl.so
llvm-readelf -h -d -Ws path/to/libltw.so
llvm-readelf -n path/to/libltw.so       # Build ID for symbolication
adb logcat -b crash -d
```

Symbolicate the tombstone/hs_err problematic frame using the **matching unstripped
build**, not a newly rebuilt library from a different commit.

| First failure | Investigation |
|---|---|
| Wrong ELF class/machine | APK/process/runtime/native ABI selection. |
| `dlopen failed: library ... not found` | Exact provider/dependency path and Android namespace. |
| `cannot locate symbol ...` | Matched dependency versions, DT_NEEDED/SONAME closure and exported symbols. |
| `No implementation found` / `UnsatisfiedLinkError` for a Java native | Java module ↔ JNI library revision, VM/classloader and native registration. |
| `NoSuchMethodError` / `NoClassDefFoundError` | Java classpath substitution/ordering; not automatically a GPU problem. |
| `EGL_BAD_MATCH` / `EGL_BAD_CONFIG` | Host API/config/context attributes and provider consistency. |
| Null PC / GL initialization SIGSEGV | Null function pointer, bad context, handle truncation, initialization ignored; symbolize before choosing a fix. |
| Driver SIGSEGV after background/resume | Surface lifetime, synchronization and context-loss handling; compare with a minimal reproducer. |
| Shader compile / missing GL feature | Renderer functionality/capability requirements; not solved by library renaming. |

### Test sequence

1. **Negative loader tests:** requested provider absent/wrong ABI/missing EGL symbol;
   no config; context creation failure; make-current failure. All must stop with a
   specific error, never fall back to unrelated system/legacy GL or dereference null.
2. **Argument/resolver unit tests:** conflicting user and manifest `-D` properties;
   inherited manifests; unknown future LWJGL versions; stale component directories;
   ARM32 process on ARM64 hardware; interrupted unpack; duplicate sonames.
3. **Real embedded-JVM LWJGL smoke:** core JNI call, STB/FreeType helper, window init,
   create/make-current, actual shader compile, VAO/VBO/texture/FBO render + readback,
   required sync/buffer features, swap and teardown. Run GLFW and SDL separately
   through their real providers. Add the test to CI, not only the Android UI test.
4. **Surface lifecycle:** repeated home/resume, surface replacement and resize;
   no stale handles, double releases, concurrent context ownership or continuing GL
   calls after lost current context.
5. **Phone acceptance:** ARM64 first, then ARM32 with matching Java 25; vanilla exact
   26.x builds, title screen → new world → movement/chunks → save/reload → resume.
   Use the same device/game/renderer/settings as the working Mojo/Zalith 2 baseline.
   Add Fabric/Sodium only after vanilla works. Record FPS/heap behavior separately
   from functional correctness.

## 7. Suggested implementation commits, in order

1. **Diagnostics + fail-closed EGL initialization:** dispatch validation, null-safe
   parsing, return failure to GLFW, early zero-context return, no unconditional
   Vulkan load. Include native negative tests.
2. **Immutable native launch plan:** manifest-based backend/bundle resolver, exact
   process ABI, locked final VM properties and absolute provider paths. Keep the
   approved current bundles while changing selection semantics.
3. **One coherent modern LWJGL bundle:** verified Android Java/native modules,
   version/hash-specific extraction, JNI/ELF/dependency audit, real JVM GL smoke.
4. **Surface/context state machine:** synchronized ownership and thread-affine EGL
   lifecycle, explicit failure propagation and lost-context policy.
5. **MobileGlues integration:** reproducible AAR + matching provider chain + phone
   tests, then expose it as an alternative. Keep LTW baseline and old game regressions.

No new release should describe 26.x as fixed until the actual ARM32/ARM64 game tests
pass. The currently published beta.11 is left untouched by this plan.

## Source references

- [Mojo substitutions](https://github.com/MojoLauncher/MojoLauncher/blob/1b8d90424c1cb791321d45f60ac62c54f4b013fa/app_mojo/src/main/assets/substitutions.json)
- [Mojo GameRunner](https://github.com/MojoLauncher/MojoLauncher/blob/1b8d90424c1cb791321d45f60ac62c54f4b013fa/app_mojo/src/main/java/git/artdeell/mojo/utils/jre/GameRunner.java)
- [Mojo loader hook](https://github.com/MojoLauncher/MojoLauncher/blob/1b8d90424c1cb791321d45f60ac62c54f4b013fa/app_mojo/src/main/jni/jvm_hooks/lwjgl_dlopen_hook.c)
- [Mojo submodule pins](https://github.com/MojoLauncher/MojoLauncher/blob/1b8d90424c1cb791321d45f60ac62c54f4b013fa/.gitmodules): GLFW `7a6c6ceb51f20cd0db84dec2914ac1a0237051f6`, mojoexec `9ec4b836e93900135bad823eaac16ebc81cf6b98`, MojoSDL `c63845bd49c5a7667d1de0f6134f85ed1ccc8889`.
- [MojoSDL EGL dispatch/context policy](https://github.com/MojoLauncher/MojoSDL/blob/c63845bd49c5a7667d1de0f6134f85ed1ccc8889/src/video/SDL_egl.c)
- [mojoexec renderer acquisition](https://github.com/MojoLauncher/mojoexec/blob/9ec4b836e93900135bad823eaac16ebc81cf6b98/mojoexec.c)
- [Zalith 2 launch arguments/components](https://github.com/ZalithLauncher/ZalithLauncher2/blob/4607f153fcce149a8e0ad39dc93fc447589d9f17/ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/launch/LaunchArgs.kt)
- [Zalith 2 renderer environment](https://github.com/ZalithLauncher/ZalithLauncher2/blob/4607f153fcce149a8e0ad39dc93fc447589d9f17/ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/launch/GameLauncher.kt)
- [Zalith 2 MobileGlues configuration](https://github.com/ZalithLauncher/ZalithLauncher2/blob/4607f153fcce149a8e0ad39dc93fc447589d9f17/ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/renderer/renderers/MobileGluesRenderer.kt)
- [Zalith 2 native bridge](https://github.com/ZalithLauncher/ZalithLauncher2/blob/4607f153fcce149a8e0ad39dc93fc447589d9f17/ZalithLauncher/src/main/jni/ctxbridges/gl_bridge.c)
- Local fixtures: `tests/fixtures/modern_versions.json`; existing safeguards:
  `tests/test_modern_compatibility.py`, `tests/test_mojo_rustgl.py`.

No upstream runtime code was copied into this fork for this plan. Any later source
port must retain the upstream licenses and attribution, including component-specific
terms and corresponding-source obligations.
