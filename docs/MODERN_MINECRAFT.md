# Minecraft 1.21.11 and 26.x: launch compatibility

## What this change fixes

* The embedded JVM, Android native libraries, extracted mod natives and renderer plugins follow the
  **running APK ABI**, not the phone's highest supported ABI. Installing the ARM32 APK on an ARM64
  phone must never extract/load an ARM64 Java runtime. The system library search path and RAM UI
  likewise use process bitness.
* Java selection filters incompatible architectures and corrupt runtime metadata, then chooses the
  nearest sufficient Java major. A compatible explicit choice is retained. An incompatible choice is
  replaced for this launch only, without changing profiles or settings. If no compatible runtime exists,
  the launch stops with an actionable error, not a fallback to Java 8 or the wrong-ABI JVM.
* LWJGL 3.4.1 natives are unpacked into ABI-specific private directories. Every extracted native's ELF
  class/machine is checked. Updating the native AAR changes the component version even if the jars are
  identical; partial extraction is retried. The version marker is written last.
* SDL renderer policy is applied even when an imported profile has no release date. SDL requires the
  supported LTW/EGL-front route; an unavailable LTW renderer is an error rather than silently trying
  incompatible GL4ES. This does not change the saved renderer preference.
* CI checks Java runtime selection and ELF headers, and inspects every generated APK for the modern
  GLFW stub, SDL patches, Java runtimes, LTW EGL front, SDL3 and matching LWJGL native files.

## Existing graphics paths retained

| Game metadata | Launcher path |
| --- | --- |
| GLFW, LWJGL 3.3.x (including 1.21.11) | Existing GLFW bridge and LWJGL 3.3.6 component; renderer policy selects LTW for modern vanilla versions |
| GLFW, LWJGL 3.4.x | GLFW stub followed by bundled LWJGL 3.4.1 jars and matching natives |
| `org.lwjgl:lwjgl-sdl` dependency | SDL3 Android window/input bridge, patched SDL classes, LTW and `libltw_sdl_egl.so` |

The EGL front already translates desktop OpenGL context/config requests into GLES 3 requests and sends
context creation/current/destruction through LTW. It is built for both ARM32 and ARM64; desktop OpenGL
is supplied by the renderer, not by pretending the phone's EGL implements desktop GL. Vulkan is not
required for this LTW path. Do not force Vulkan or arbitrarily spoof OpenGL capabilities on a GE8300.
No new SDL/renderer binary was substituted in this change. Attribution remains in THIRD_PARTY_NOTICES.md.

Java requirements come from the resolved game manifest. Bundled Java 21 and 25 have ARM32, ARM64 and
x86_64 variants. **Java 25 is not bundled for 32-bit x86**; versions requiring it cannot run on that ABI
with the shipped runtimes. An APK being available for x86 does not make Java 25 available there.
An arbitrary future 26.x release, LWJGL ABI revision or modpack is not automatically supported merely
because its version starts with `26`.

## Validation and remaining device tests

Automated checks validate code selection, packaging and binary architecture. They are **not evidence
of reaching the title screen or playing a world on Android**. This environment has no Android device/GPU.

Run vanilla first, with a fresh game profile (keep existing worlds/settings backed up):

1. On the target Helio A22/PowerVR GE8300 phone, test the ARM32 APK and, if Android supports a 64-bit
   app process, the ARM64 APK. A 64-bit CPU does not necessarily imply a 64-bit Android installation.
2. Test 1.21.11, a 26.x GLFW release, and a 26.x SDL release/snapshot. Confirm logged runtime major and
   process ABI; use LTW, not GL4ES, for modern vanilla Minecraft.
3. Reach the title screen, create/load a world, move/look/type, resize/rotate, background/resume and quit.
4. Verify both runtime and native extraction repair after upgrading between single-ABI and universal APKs.
5. Repeat with desired mods only after vanilla works. Report exact version, APK ABI, Android version,
   launcher/game log and `hs_err_pid`/native crash output if it fails. Keep access tokens out of logs shared publicly.

On a 32-bit app process, native libraries, graphics resources, thread stacks and Java share a limited
address space. Raising Xmx is not a universal fix; low-memory gameplay and all-version 500 MB claims
require device measurements, not just a successful APK build.

## Upstream references inspected

* LTW's bundled commit: https://github.com/MojoLauncher/LTW/tree/2f4dbaaa2494a9fe73d78dda859301b5c060fdd3
* Version catalog: https://github.com/misode/mcmeta/tree/summary/versions
  (catalog checked through 26.4-snapshot-7; this is version information, not device compatibility evidence).
* SDL/LWJGL component origins: THIRD_PARTY_NOTICES.md.
