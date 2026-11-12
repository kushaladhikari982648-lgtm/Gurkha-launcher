# 26.x launch hardening — alpha.1 development

This implements concrete failure-path repairs from `26X_RENDERING_MIGRATION.md`.
It is not a claim that the reported exit -3 was reproduced or fixed on a phone.
The user could not send logs because of a low-memory warning; logs are not a
prerequisite for these source-backed repairs. Beta.11's published APKs are unchanged.

## Implemented

- Explicit EGL provider loading uses RTLD_NOW, never falls back to system EGL on
  failure, and validates the complete required dispatch before publishing it.
- EGL initialization failure reaches GLFW; unknown renderer and missing surface
  fail cleanly. GLES-based renderers no longer eagerly initialize Vulkan.
- Safe host-version parsing, ES1/2/3 or desktop-EGL config selection, checked bind /
  config / visual / context / make-current operations and no swap without a current
  bundle. Context creation failure never registers a bogus GLFW window. Failed GL
  operations can throw an actionable error on the calling HotSpot thread, not ART.
- A mutex-protected native-window slot owns one published reference. Render threads
  acquire snapshots with a generation number. EGL operations never hold that mutex.
  GL and OSMesa consume owned references rather than raw UI-thread pointers.
  Surface detach is published from both Android surface implementations. Repeated
  detach is safe. TextureView-owned Surface objects are released by their owner.
- GL surface changes unbind first; missing windows use a 1x1 pbuffer. Context loss
  stops GL dispatch instead of claiming that replacing a surface restores GL objects.
  Termination uses the real current bundle rather than the unused PotatoBridge and
  does not terminate Android's shared EGL display. Full per-window/shared-context
  destruction remains a follow-up; the existing game-process exit still reclaims
  contexts not current at termination.
- SDL never enters the legacy GLFW context path. LTW's SDL front reports failed
  provider initialization instead of aborting, and preserves a logged context-
  creation EGL error for the caller. Renderer library selection remains unchanged.
- VM arguments and main/game arguments are separate. Launcher-owned native
  properties are applied after user/manifest VM arguments but before the main class.
  Core LWJGL and renderer paths are absolute. A selected renderer load no longer
  retries an unrelated file from the search path. Plugin preload failure is reported.
- Explicit modern substitutions: requested 3.4.1 or 3.4.3 -> existing Android 3.4.1
  JNI bundle (the preexisting API-audited substitution). Unknown modern requests
  stop rather than silently using a legacy bundle. This is NOT a new 3.4.3 binary port.
- Modern native cache is keyed by the pinned AAR digest and process ABI; a shipped
  SHA-256 manifest checks every native across all four ABIs. Damaged cache entries
  trigger repair during unpack; launch also checks hashes and architecture.
- Low-end mode starts at Xms=min(Xmx,128 MB), leaving the user's Xmx unchanged.
  It does not suppress RAM warnings or claim that 26.x will fit in a tiny heap.
- Log sharing streams at most 64 KiB header + 256 KiB tail per file, only two recent
  launcher logs plus latest crash/game logs. Originals are not truncated. Share
  errors are surfaced. The launcher no longer prints the entire raw game argument
  array; common authentication argument values are redacted in its argument log.

## Tests

`tests/test_native_launch_hardening.py` compiles and executes the actual production
EGL loader, GL bridge and window slot against a controlled fake EGL provider:
missing provider, missing symbol, malformed ES version, API bind failure, zero
configs, failed context, failed make-current, context loss, surface replacement,
double detach and concurrent snapshot/publication reference ownership. These tests
are deterministic contract checks, not GPU-driver/game compatibility tests.

Java tests execute final native-property precedence, explicit substitutions and
bounded log copying (including an 8 MiB input). Bundle tests compare the manifest
against actual four-ABI contents of the committed AAR. Existing native builds,
Java/ABI checks, APK builds, launcher UI and surface-only recorder tests still apply.

Local C/Python tests are available without an Android SDK. Tests requiring a JDK
are skipped locally when unavailable and executed in Android CI. Check the run's
actual outcome before treating APK/device validation as complete.

## Still outstanding — do not describe these as implemented

- Replacing the existing Java/native modules with a new complete Mojo 3.4.3-r6
  bundle and its coordinated SDL/GLFW glue; no mixed new jars/old JNI were introduced.
- Porting MobileGlues and its optional wrapper chain. LTW remains the modern
  baseline; RustGL remains experimental. No Freedreno or forced Vulkan was added.
- A full native dependency/SONAME namespace redesign and removal of the JRE's bulk
  preload behavior; a per-ABI SHA-256 manifest cannot by itself fix interposition.
- Immutable atomic publication of the whole jar/native tree while another game
  process is using it. Existing unpack lifecycle/repair behavior is retained.
- Actual embedded-JVM GL shader/readback tests and on-phone ARM32/ARM64 26.x world,
  lifecycle and low-memory tests. Existing UI/recording smoke tests are not these.
- Automatic recovery/recreation of game resources after EGL_CONTEXT_LOST. Current
  behavior is a clear failure rather than unsafe continued rendering.

No Latest/non-prerelease publication is made solely on these tests. The requested
alpha.1 identity is used for development APKs so they are distinguishable from the
released beta.11, with a higher Android version code (100011).
