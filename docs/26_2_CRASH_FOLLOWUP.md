# Alpha.2: follow-up to the vivo V2026 26.2 log

## Evidence, not a diagnosis

The user supplied an alpha.1 (100011) log: ARM64, Android 11/API 30,
Minecraft 26.2, LTW, Internal-25, 936 MB maximum heap / 128 MB initial heap.
LWJGL was correctly routed to the private Android 3.4.1 JNI bundle with GLFW.
Repeated logcat EOFs exhausted the collector. A basename libjvm.so load failed,
but the old loader then attempted the selected runtime's full path; its result
was sent only to logcat and is absent from the pasted report. This does NOT prove
that libjvm.so is missing. The report stops while arguments are being printed,
with no JLI return, Java exception, signal or fatal-error record.

The original -3 remains undiagnosed. Java 8 -> Java 25 selection is intentional
for this version. Nothing here establishes an LTW, Vulkan or heap-size failure.

## Source-justified repairs

- Resolve JLI and server/client JVM libraries inside the selected runtime. Reject
  missing/wrong-ABI/failed critical libraries before entering JLI. The VM directory
  now appears in both linker setup and exported LD_LIBRARY_PATH. JLI startup uses
  the same absolute selected path as preloading. Legacy Java 8 arch layouts remain
  supported, resetting the directory before resolving a new runtime.
- dlopen diagnostics, JLI entry/return and exit-hook status go to the native log
  file path (direct stderr capture or direct file append), independent of logcat.
- Supplemental logcat is PID-filtered, uses a small initial tail, never clears
  history and stops after EOF instead of recursively spawning 11 collectors.
  The existing direct stdout/stderr logger remains active.
- The actual native logger no longer runs strstr on non-NUL-terminated pipe data,
  or allocates a stack array proportional to a Java log string. Reads/writes handle
  EINTR/partial writes; appends are serialized; UI listener references are acquired
  safely and listener exceptions cannot stop the native file reader. File-open and
  thread/pipe setup failures are reported, and reopening reuses the existing reader.
- Manifest -cp/--class-path entries are removed before the launcher's patched
  classpath is appended. Launcher brand/version placeholders are substituted.
- Final java.library.path uses the flat APK native directory and (where needed)
  the approved modern bundle and version cache. Final JNA bootstrap uses the APK
  directory; JNA and Netty extraction directories use writable private cache, not
  desktop-style /data/app/.../lib/arm64/{java,jna,netty} paths from the manifest.

No renderer replacement, capability spoofing, RAM-limit change or blanket "ignore
unrecognized VM options" flag was added. Arbitrary desktop native binaries are not
made Android-compatible by fixing an extraction path.

## Regression coverage

- Guard-page test passes a full unterminated buffer and boundary-length buffers to
  the production log filter, including a marker at the final valid byte.
- Actual production stdio_is.c is compiled into a JNI test library. Java invokes
  Logger.begin/append/setLogListener without any logcat, exercises failed open,
  a 256 KiB append, throwing listener, pipe capture and reopening the log.
- Pure Java tests cover modern/legacy JLI layouts, server/client preference,
  missing runtime rejection, placeholder substitution, duplicate manifest classpath
  removal and final native-property precedence. Existing bridge tests still apply.

Host and Android CI tests do not reproduce the user's game or prove that -3 is fixed.
Alpha.2 (100012) is a development build, not a confirmed-working Latest release.
