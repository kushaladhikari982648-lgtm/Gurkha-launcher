package net.kdt.pojavlaunch.utils;

import net.kdt.pojavlaunch.MinecraftGLSurface;
import com.movtery.zalithlauncher.launch.SdlSupport;
import com.movtery.zalithlauncher.launch.LowMemoryProfile;
import com.movtery.zalithlauncher.utils.platform.LowEndPolicy;
import static com.movtery.zalithlauncher.utils.path.PathManager.DIR_NATIVE_LIB;
import static net.kdt.pojavlaunch.Architecture.ARCH_X86;
import static net.kdt.pojavlaunch.Architecture.is64BitProcess;
import static net.kdt.pojavlaunch.Tools.currentDisplayMetrics;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ArrayMap;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.movtery.zalithlauncher.InfoDistributor;
import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.event.value.JvmExitEvent;
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathHome;
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathManager;
import com.movtery.zalithlauncher.feature.log.Logging;
import com.movtery.zalithlauncher.feature.version.Version;
import com.movtery.zalithlauncher.feature.version.VersionInfo;
import com.movtery.zalithlauncher.plugins.driver.DriverPluginManager;
import com.movtery.zalithlauncher.plugins.renderer.RendererPluginManager;
import com.movtery.zalithlauncher.plugins.renderer.RendererPlugin;
import com.movtery.zalithlauncher.renderer.RendererInterface;
import com.movtery.zalithlauncher.renderer.Renderers;
import com.movtery.zalithlauncher.setting.AllSettings;
import com.movtery.zalithlauncher.ui.activity.ErrorActivity;
import com.movtery.zalithlauncher.utils.ZHTools;
import com.movtery.zalithlauncher.utils.path.LibPath;
import com.movtery.zalithlauncher.utils.path.PathManager;
import com.oracle.dalvik.VMLauncher;

import net.kdt.pojavlaunch.Architecture;
import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.MainActivity;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.plugins.FFmpegPlugin;
import net.kdt.pojavlaunch.services.GameService;

import org.greenrobot.eventbus.EventBus;
import org.lwjgl.glfw.CallbackBridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

public final class JREUtils {
    private JREUtils() {}

    public static String LD_LIBRARY_PATH;
    public static String jvmLibraryPath;

    public static String findInLdLibPath(String libName) {
        if (Os.getenv("LD_LIBRARY_PATH")==null) {
            try {
                if (LD_LIBRARY_PATH != null) {
                    Os.setenv("LD_LIBRARY_PATH", LD_LIBRARY_PATH, true);
                }
            }catch (ErrnoException e) {
                Logging.e("JREUtils", Tools.printToString(e));
            }
            return libName;
        }
        for (String libPath : Os.getenv("LD_LIBRARY_PATH").split(":")) {
            File f = new File(libPath, libName);
            if (f.exists() && f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        return libName;
    }

    public static ArrayList<File> locateLibs(File path) {
        ArrayList<File> returnValue = new ArrayList<>();
        File[] list = path.listFiles();
        if (list != null) {
            for(File f : list) {
                if (f.isFile() && f.getName().endsWith(".so")) {
                    String name = f.getName();
                    if ("libfreetype.so".equals(name) || "libjsig.so".equals(name)) continue;
                    returnValue.add(f);
                }else if (f.isDirectory()) {
                    returnValue.addAll(locateLibs(f));
                }
            }
        }
        return returnValue;
    }

    public static void initJavaRuntime(String jreHome) {
        File libDir = new File(jreHome, Tools.DIRNAME_HOME_JRE);
        File jli = RuntimeLibraryPaths.jli(libDir);
        File jvm = RuntimeLibraryPaths.jvm(libDir);
        Logger.appendToLog("Runtime loader: JLI=" + jli + "; JVM=" + jvm);
        requireRuntimeLibrary(jli);
        requireRuntimeLibrary(jvm);
        try {
            Os.setenv("POJAV_JLI_PATH", jli.getAbsolutePath(), true);
        } catch (android.system.ErrnoException e) {
            throw new IllegalStateException("Cannot publish selected JLI path", e);
        }
        dlopen(findInLdLibPath("libverify.so"));
        requireRuntimeLibrary(new File(libDir, "libjava.so"));
        // dlopen(findInLdLibPath("libjsig.so"));
        dlopen(findInLdLibPath("libnet.so"));
        dlopen(findInLdLibPath("libnio.so"));
        dlopen(findInLdLibPath("libawt.so"));
        dlopen(findInLdLibPath("libawt_headless.so"));
        File modernFreetype = new File(DIR_NATIVE_LIB, "libfreetype.so");
        dlopen(modernFreetype.isFile() ? modernFreetype.getAbsolutePath() : findInLdLibPath("libfreetype.so"));
        dlopen(findInLdLibPath("libfontmanager.so"));
        for (File f : locateLibs(new File(jreHome, Tools.DIRNAME_HOME_JRE))) {
            dlopen(f.getAbsolutePath());
        }
    }

    private static void requireRuntimeLibrary(File library) {
        if (!NativeLibraryInfo.matches(library, Tools.DEVICE_ARCHITECTURE) || !dlopen(library.getAbsolutePath())) {
            String message = "Required runtime library failed: " + library +
                    "; see RuntimeLoader diagnostics. Reinstall the selected Java runtime if incomplete.";
            Logger.appendToLog(message);
            throw new IllegalStateException(message);
        }
    }

    public static void redirectAndPrintJRELog() {
        // Supplemental driver diagnostics only. stdout/stderr are already captured by Logger.begin.
        // Never clear the ring buffer or recursively relaunch a collector rejected by an OEM logd.
        new Thread(() -> {
            Process process = null;
            try {
                process = new ProcessBuilder("logcat", "--pid=" + android.os.Process.myPid(),
                        "-T", "1", "-v", "brief", "-s", "jrelog:I", "LIBGL:I", "NativeInput",
                        "GLBridge:I", "LtwSdlEgl:I").redirectErrorStream(true).start();
                try (java.io.Reader reader = new java.io.InputStreamReader(process.getInputStream(),
                        java.nio.charset.StandardCharsets.UTF_8)) {
                    char[] buffer = new char[2048];
                    int count;
                    while ((count = reader.read(buffer)) != -1) Logger.appendToLog(new String(buffer, 0, count));
                }
                Logger.appendToLog("Supplemental logcat stopped (exit " + process.waitFor() +
                        "). Native stdout/stderr capture remains active; logcat EOF is not a game exit code.");
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                Logger.appendToLog("Supplemental logcat unavailable: " + e + "; native stdout/stderr capture remains active.");
            } finally {
                if (process != null) process.destroy();
            }
        }, "SupplementalLogcat").start();
    }

    public static void relocateLibPath(Runtime runtime, String jreHome) {
        Tools.DIRNAME_HOME_JRE = "lib"; // Do not retain an earlier Java 8 arch-specific directory.
        String JRE_ARCHITECTURE = runtime.arch;
        if (Architecture.archAsInt(JRE_ARCHITECTURE) == ARCH_X86){
            JRE_ARCHITECTURE = "i386/i486/i586";
        }

        for (String arch : JRE_ARCHITECTURE.split("/")) {
            File f = new File(jreHome, "lib/" + arch);
            if (f.exists() && f.isDirectory()) {
                Tools.DIRNAME_HOME_JRE = "lib/" + arch;
            }
        }

        String libName = is64BitProcess() ? "lib64" : "lib";
        StringBuilder ldLibraryPath = new StringBuilder();
        if (FFmpegPlugin.isAvailable) {
            ldLibraryPath.append(FFmpegPlugin.libraryPath).append(":");
        }
        RendererPlugin customRenderer = RendererPluginManager.getSelectedRendererPlugin();
        if (customRenderer != null) {
            ldLibraryPath.append(customRenderer.getPath()).append(":");
        }
        ldLibraryPath.append(jreHome)
                .append("/").append(Tools.DIRNAME_HOME_JRE)
                .append("/jli:").append(jreHome).append("/").append(Tools.DIRNAME_HOME_JRE)
                .append(":");
        ldLibraryPath.append("/system/").append(libName).append(":")
                .append("/vendor/").append(libName).append(":")
                .append("/vendor/").append(libName).append("/hw:");
        File runtimeModDir = PathManager.DIR_RUNTIME_MOD;
        if (runtimeModDir != null) {
            ldLibraryPath.append(runtimeModDir.getAbsolutePath()).append(":");
        }
        ldLibraryPath.append(DIR_NATIVE_LIB);
        LD_LIBRARY_PATH = ldLibraryPath.toString();
    }

    private static void initLdLibraryPath(String jreHome) {
        jvmLibraryPath = RuntimeLibraryPaths.jvm(new File(jreHome, Tools.DIRNAME_HOME_JRE)).getParent();
        // JLI and libraries loaded later must see the same VM directory as our explicit preload.
        LD_LIBRARY_PATH = jvmLibraryPath + ":" + LD_LIBRARY_PATH;
        Logger.appendToLog("Runtime loader: library search path=" + LD_LIBRARY_PATH);
        setLdLibraryPath(LD_LIBRARY_PATH);
    }

    private static void setJavaEnv(Map<String, String> envMap, String jreHome) {
        envMap.put("POJAV_NATIVEDIR", DIR_NATIVE_LIB);
        envMap.put("DRIVER_PATH", DriverPluginManager.getDriver().getPath());
        envMap.put("JAVA_HOME", jreHome);
        envMap.put("HOME", PathManager.DIR_GAME_HOME);
        envMap.put("TMPDIR", PathManager.DIR_CACHE.getAbsolutePath());
        envMap.put("LD_LIBRARY_PATH", LD_LIBRARY_PATH);
        envMap.put("PATH", jreHome + "/bin:" + Os.getenv("PATH"));
        envMap.put("FORCE_VSYNC", String.valueOf(AllSettings.getForceVsync().getValue()));
        envMap.put("AWTSTUB_WIDTH", Integer.toString(CallbackBridge.windowWidth > 0 ? CallbackBridge.windowWidth : CallbackBridge.physicalWidth));
        envMap.put("AWTSTUB_HEIGHT", Integer.toString(CallbackBridge.windowHeight > 0 ? CallbackBridge.windowHeight : CallbackBridge.physicalHeight));
        envMap.put("MOD_ANDROID_RUNTIME", PathManager.DIR_RUNTIME_MOD != null ? PathManager.DIR_RUNTIME_MOD.getAbsolutePath() : "");

        if (AllSettings.getDumpShaders().getValue())
            envMap.put("LIBGL_VGPU_DUMP", "1");
        if (AllSettings.getZinkPreferSystemDriver().getValue())
            envMap.put("POJAV_ZINK_PREFER_SYSTEM_DRIVER", "1");
        if (AllSettings.getVsyncInZink().getValue())
            envMap.put("POJAV_VSYNC_IN_ZINK", "1");
        if (AllSettings.getBigCoreAffinity().getValue())
            envMap.put("POJAV_BIG_CORE_AFFINITY", "1");
        if (FFmpegPlugin.isAvailable)
            envMap.put("POJAV_FFMPEG_PATH", FFmpegPlugin.executablePath);
    }

    private static void setRendererEnv(Map<String, String> envMap) {
        RendererInterface currentRenderer = Renderers.INSTANCE.getCurrentRenderer();
        String rendererId = currentRenderer.getRendererId();

        if (rendererId.startsWith("opengles2")) {
            envMap.put("LIBGL_ES", "2");
            envMap.put("LIBGL_MIPMAP", "3");
            envMap.put("LIBGL_NOERROR", "1");
            envMap.put("LIBGL_NOINTOVLHACK", "1");
            envMap.put("LIBGL_NORMALIZE", "1");
        }

        envMap.putAll(currentRenderer.getRendererEnv().getValue());

        String eglName = currentRenderer.getRendererEGL();
        if (eglName != null) envMap.put("POJAVEXEC_EGL", resolveRendererFile(eglName));
        else envMap.remove("POJAVEXEC_EGL");
        // Never inherit another renderer/backend's context provider from custom_env.txt.
        for (String key : new String[]{"SDL_EGL_LIBRARY", "SDL_OPENGL_LIBRARY", "LTW_SDL_EGL_LTW_LIBRARY"}) envMap.remove(key);

        // A library loaded by SDL must never initialize the launcher's GLFW-only fields.
        envMap.put("POJAV_WINDOW_BACKEND", MinecraftGLSurface.sdlVersion ? "sdl" : "glfw");
        if (MinecraftGLSurface.sdlVersion) {
            // In SDL3 mode (26.3+), do not set POJAV_RENDERER: GL.createCapabilities() in
            // lwjgl-opengl.jar checks System.getenv("POJAV_RENDERER") != null to decide whether
            // to call GL.fixPojavGLContext(), which reflects org.lwjgl.glfw.GLFW.
            envMap.remove("POJAV_RENDERER");
            // Minecraft 26.3+: SDL creates the GL context itself and needs the renderer's libraries
            envMap.putAll(SdlSupport.getRendererEnv(currentRenderer, DIR_NATIVE_LIB));
        } else {
            envMap.put("POJAV_RENDERER", rendererId);
        }

        if (RendererPluginManager.getSelectedRendererPlugin() != null) return;

        if (!rendererId.startsWith("opengles")) {
            envMap.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
            envMap.put("MESA_GLSL_CACHE_DIR", PathManager.DIR_CACHE.getAbsolutePath());
            envMap.put("force_glsl_extensions_warn", "true");
            envMap.put("allow_higher_compat_version", "true");
            envMap.put("allow_glsl_extension_directive_midshader", "true");
            envMap.put("LIB_MESA_NAME", loadGraphicsLibrary());
        }

        if (!envMap.containsKey("LIBGL_ES")) {
            int glesMajor = getDetectedVersion();
            Logging.i("glesDetect","GLES version detected: "+glesMajor);

            if (glesMajor < 3) {
                //fallback to 2 since it's the minimum for the entire app
                envMap.put("LIBGL_ES","2");
            } else if (rendererId.startsWith("opengles")) {
                envMap.put("LIBGL_ES", rendererId.replace("opengles", "").replace("_5", ""));
            } else {
                // TODO if can: other backends such as Vulkan.
                // Sure, they should provide GLES 3 support.
                envMap.put("LIBGL_ES", "3");
            }
        }
    }

    private static void setCustomEnv(Map<String, String> envMap) throws Throwable {
        File customEnvFile = new File(PathManager.DIR_GAME_HOME, "custom_env.txt");
        if (customEnvFile.exists() && customEnvFile.isFile()) {
            BufferedReader reader = new BufferedReader(new FileReader(customEnvFile));
            String line;
            while ((line = reader.readLine()) != null) {
                int index = line.indexOf("=");
                envMap.put(line.substring(0, index), line.substring(index + 1));
            }
            reader.close();
        }
    }

    private static void checkAndUsedJSPH(Map<String, String> envMap, final Runtime runtime) {
        boolean onUseJSPH = runtime.javaVersion > 11;
        if (!onUseJSPH) return;
        File dir = new File(DIR_NATIVE_LIB);
        if (!dir.isDirectory()) return;
        String jsphName = runtime.javaVersion == 17 ? "libjsph17" : "libjsph21";
        File[] files = dir.listFiles((dir1, name) -> name.startsWith(jsphName));
        if (files != null && files.length > 0) {
            String libName = DIR_NATIVE_LIB + "/" + jsphName + ".so";
            envMap.put("JSP", libName);
        }
    }

    private static void setEnv(String jreHome, final Runtime runtime, Version gameVersion) throws Throwable {
        Map<String, String> envMap = new ArrayMap<>();

        setJavaEnv(envMap, jreHome);
        setCustomEnv(envMap);

        if (gameVersion != null) {
            checkAndUsedJSPH(envMap, runtime);

            VersionInfo versionInfo = gameVersion.getVersionInfo();
            if (versionInfo != null && versionInfo.getLoaderInfo() != null) {
                for (VersionInfo.LoaderInfo loaderInfo : versionInfo.getLoaderInfo()) {
                    if (loaderInfo.getLoaderEnvKey() != null) {
                        envMap.put(loaderInfo.getLoaderEnvKey(), "1");
                    }
                }
            }

            if (Renderers.INSTANCE.isCurrentRendererValid()) {
                setRendererEnv(envMap);
            }

            envMap.put("ZALITH_VERSION_CODE", String.valueOf(ZHTools.getVersionCode()));
        }

        for (Map.Entry<String, String> env : envMap.entrySet()) {
            Logger.appendToLog("Added custom env: " + env.getKey() + "=" + env.getValue());
            try {
                Os.setenv(env.getKey(), env.getValue(), true);
            }catch (NullPointerException exception){
                Logging.e("JREUtils", exception.toString());
            }
        }
    }

    private static void initGraphicAndSoundEngine(boolean renderer) {
        dlopen(DIR_NATIVE_LIB + "/libopenal.so");

        if (!renderer) return;

        String rendererLib = loadGraphicsLibrary();
        RendererPlugin customRenderer = RendererPluginManager.getSelectedRendererPlugin();

        if (customRenderer != null) {
            customRenderer.getDlopen().forEach(lib -> {
                String path = resolveRendererFile(lib);
                if (!dlopen(path)) throw new IllegalStateException("Failed to load renderer dependency: " + path);
            });
        }

        if (!dlopen(rendererLib)) {
            throw new IllegalStateException("Failed to load renderer " + rendererLib + "; check the renderer/ABI and native loader log");
        }
    }

    private static void launchJavaVM(
            final AppCompatActivity activity,
            String runtimeHome,
            final Runtime runtime,
            Version gameVersion,
            final List<String> JVMArgs,
            final List<String> mainArgs,
            final String userArgsString
    ) {
        List<String> userArgs = getJavaArgs(runtimeHome, userArgsString);
        //Remove arguments that can interfere with the good working of the launcher
        purgeArg(userArgs,"-Xms");
        purgeArg(userArgs,"-Xmx");
        purgeArg(userArgs,"-d32");
        purgeArg(userArgs,"-d64");
        purgeArg(userArgs, "-Xint");
        purgeArg(userArgs, "-XX:+UseTransparentHugePages");
        purgeArg(userArgs, "-XX:+UseLargePagesInMetaspace");
        purgeArg(userArgs, "-XX:+UseLargePages");
        purgeArg(userArgs, "-Dorg.lwjgl.opengl.libname");
        // Don't let the user specify a custom Freetype library (as the user is unlikely to specify a version compiled for Android)
        purgeArg(userArgs, "-Dorg.lwjgl.freetype.libname");
        // Overridden by us to specify the exact number of cores that the android system has
        purgeArg(userArgs, "-XX:ActiveProcessorCount");

        userArgs.add("-javaagent:" + LibPath.MIO_LIB_PATCHER.getAbsolutePath());

        //Add automatically generated args
        userArgs.add("-Xms" + LowEndPolicy.initialHeapMb(AllSettings.getRamAllocation().getValue().getValue(), AllSettings.getLowEndMode().getValue()) + "M");
        userArgs.add("-Xmx" + AllSettings.getRamAllocation().getValue().getValue() + "M");
        Map<String, String> lockedNativeProperties = new java.util.LinkedHashMap<>();
        if (Renderers.INSTANCE.isCurrentRendererValid() && gameVersion != null)
            lockedNativeProperties.put("org.lwjgl.opengl.libname", loadGraphicsLibrary());

        // Force LWJGL to use the Freetype library intended for it, instead of using the one
        // that we ship with Java (since it may be older than what's needed)
        lockedNativeProperties.put("org.lwjgl.freetype.libname", DIR_NATIVE_LIB + "/libfreetype.so");
        lockedNativeProperties.put("org.lwjgl.openal.libname", DIR_NATIVE_LIB + "/libopenal.so");
        // Minecraft 26.3+ (SDL3) and the GLFW versions on LWJGL 3.4.1 (26.2): LWJGL 3.4.1 uses its own natives
        // (overrides the freetype path above)
        if (gameVersion != null && (MinecraftGLSurface.sdlVersion || SdlSupport.lwjgl341Glfw)) {
            for (String argument : SdlSupport.getJvmArgs(DIR_NATIVE_LIB)) {
                int separator = argument.indexOf('=');
                lockedNativeProperties.put(argument.substring(2, separator), argument.substring(separator + 1));
            }
        }

        if (gameVersion != null) {
            // Metadata targets desktop layouts (e.g. ${natives_directory}/java). Android APK
            // libraries are flat; extraction must use writable private cache, not /data/app.
            File extracted = new File(PathManager.DIR_CACHE, "natives/" + gameVersion.getVersionName());
            String libraryPath = DIR_NATIVE_LIB;
            if (MinecraftGLSurface.sdlVersion || SdlSupport.lwjgl341Glfw)
                libraryPath = SdlSupport.getNativesDir().getAbsolutePath() + ":" + libraryPath;
            if (extracted.isDirectory()) libraryPath += ":" + extracted.getAbsolutePath();
            lockedNativeProperties.put("java.library.path", libraryPath);
            lockedNativeProperties.put("jna.boot.library.path", DIR_NATIVE_LIB);
            File nativeWork = new File(PathManager.DIR_CACHE, "native-work");
            if (!nativeWork.isDirectory() && !nativeWork.mkdirs())
                throw new IllegalStateException("Cannot create writable native extraction directory: " + nativeWork);
            lockedNativeProperties.put("jna.tmpdir", nativeWork.getAbsolutePath());
            lockedNativeProperties.put("io.netty.native.workdir", nativeWork.getAbsolutePath());
        }

        // Some phones are not using the right number of cores, fix that
        userArgs.add("-XX:ActiveProcessorCount=" + java.lang.Runtime.getRuntime().availableProcessors());

        // Runtime specific JVM fixes (see getArm32FixArgs). They must stay before JVMArgs,
        // which end with the main class.
        List<String> runtimeFixArgs = getArm32FixArgs(runtime, userArgs);
        if (!runtimeFixArgs.isEmpty()) {
            Logger.appendToLog("Info: 32-bit ARM runtime (Java " + runtime.javaVersion + "), adding: "
                    + String.join(" ", runtimeFixArgs));
        }
        userArgs.addAll(runtimeFixArgs);

        // Smaller heap use for small allocations (2-4 GB phones), see LowMemoryProfile
        int allocation = AllSettings.getRamAllocation().getValue().getValue();
        List<String> lowMemoryArgs = LowMemoryProfile.getJvmArgs(runtime, allocation, userArgs);
        if (!lowMemoryArgs.isEmpty()) {
            Logger.appendToLog("Info: " + allocation + " MB allocation, adding: " + String.join(" ", lowMemoryArgs));
        }
        userArgs.addAll(lowMemoryArgs);

        if (gameVersion == null) {
            // Installer callers still supply a complete command; prepend their legacy properties.
            NativeLaunchPolicy.enforceProperties(userArgs, lockedNativeProperties);
            userArgs.addAll(JVMArgs);
        } else {
            userArgs.addAll(JVMArgs); // VM arguments only; main/game args are separate.
            NativeLaunchPolicy.enforceProperties(userArgs, lockedNativeProperties);
            userArgs.addAll(mainArgs);
        }
        activity.runOnUiThread(() -> Toast.makeText(activity, activity.getString(R.string.autoram_info_msg, AllSettings.getRamAllocation().getValue().getValue()), Toast.LENGTH_SHORT).show());
        for (int i = 0; i < userArgs.size(); i++) {
            String arg = userArgs.get(i);
            if (arg.equals("--accessToken") || arg.equals("--clientId") || arg.equals("--xuid")) {
                Logger.appendToLog("JVMArg: " + arg + " [redacted]");
                i += 1;
                continue;
            }
            if (arg.startsWith("--accessToken=") || arg.startsWith("-javaagent:")) continue;
            if (i > 0 && (userArgs.get(i - 1).equals("-cp") || userArgs.get(i - 1).equals("-classpath") || userArgs.get(i - 1).equals("--class-path"))) {
                String[] entries = arg.split(File.pathSeparator);
                String first = entries.length > 0 ? new File(entries[0]).getName() : "";
                String last = entries.length > 0 ? new File(entries[entries.length - 1]).getName() : "";
                Logger.appendToLog("JVMArg: <classpath: " + entries.length + " entries, " + arg.length() + " chars, first=" + first + ", last=" + last + ">");
                continue;
            }
            Logger.appendToLog("JVMArg: " + arg);
        }

        setupExitMethod(activity.getApplication());
        initializeGameExitHook();
        chdir(gameVersion == null ? ProfilePathHome.getGameHome() : gameVersion.getGameDir().getAbsolutePath());
        userArgs.add(0,"java"); //argv[0] is the program name according to C standard.

        Logger.appendToLog("JVM startup: invoking selected Java " + runtime.javaVersion + " (native JLI)");
        final int exitCode = VMLauncher.launchJVM(userArgs.toArray(new String[0]));
        Logger.appendToLog("Java Exit code: " + exitCode);
        if (exitCode != 0) {
            ErrorActivity.showExitMessage(activity, exitCode, false);
        }
        EventBus.getDefault().post(new JvmExitEvent(exitCode));
        if (activity instanceof MainActivity) endGameProcess(activity, exitCode);
    }

    /**
     * The game returned from main() instead of calling System.exit() (Minecraft 26.2 and newer can do that when
     * "Quit Game" is used). Only System.exit() is caught by the exit hook (nominal_exit in jni/stdio_is.c), so
     * nothing ended the process: the game screen stayed on a JVM that had finished (it can't be started again
     * in the same process) and its foreground service kept running. End the process the same way the exit
     * hook does. System.exit() can't be used here: it would run into the exit hook again, which waits forever.
     * Same idea as upstream ZalithLauncher PR #758 ("stuck Quit Game 26.2-snapshot-1").
     */
    private static void endGameProcess(AppCompatActivity activity, int exitCode) {
        Logging.i("JREUtils", "The game returned from main() (exit code " + exitCode + "), ending the process");
        try {
            activity.stopService(new Intent(activity, GameService.class));
        } catch (Exception e) {
            Logging.w("JREUtils", "Failed to stop the game service: " + e);
        }
        // Give the error screen (started above for a non-zero exit code) a moment to be handed to the system,
        // which shows it in a new process, like the exit hook's "hat trick"
        new Handler(Looper.getMainLooper()).postDelayed(ZHTools::killProcess, exitCode != 0 ? 500 : 100);
    }

    public static void launchWithUtils(
            final AppCompatActivity activity,
            final Runtime runtime,
            Version gameVersion,
            final List<String> JVMArgs,
            final String userArgsString
    ) throws Throwable {
        if (gameVersion != null) throw new IllegalArgumentException("Game launches must separate VM and main arguments");
        launchWithUtils(activity, runtime, null, JVMArgs, java.util.Collections.emptyList(), userArgsString);
    }

    public static void launchWithUtils(final AppCompatActivity activity, final Runtime runtime,
            Version gameVersion, final List<String> JVMArgs, final List<String> mainArgs,
            final String userArgsString) throws Throwable {
        String runtimeHome = MultiRTUtils.getRuntimeHome(runtime.name).getAbsolutePath();

        relocateLibPath(runtime, runtimeHome);

        initLdLibraryPath(runtimeHome);

        setEnv(runtimeHome, runtime, gameVersion);

        initJavaRuntime(runtimeHome);

        initGraphicAndSoundEngine(gameVersion != null);

        launchJavaVM(activity, runtimeHome, runtime, gameVersion, JVMArgs, mainArgs, userArgsString);
    }

    /**
     * JVM options for 32-bit ARM runtimes, reproducing what PojavLauncher changed in its own arm32 Java
     * builds (those changes live in PojavLauncherTeam/android-openjdk-build-multiarch, not in the
     * launcher, so the runtimes bundled with this launcher do not have them).
     * <p>
     * <b>Client VM (Java 17+).</b> Pojav builds its arm32 runtimes as the "client" VM since February 2025
     * (android-openjdk-build-multiarch PR #29, "After testing by me and several other people on arm32,
     * the VM appears to run more stable with this"). The bundled arm32 runtimes are "server" VMs, which on
     * 32-bit ARM run the interpreter plus the C2 compiler only (TieredCompilation is off by default
     * there): C2 is slow to kick in and needs a lot of memory, which a 32-bit process does not have.
     * -XX:+NeverActAsServerClassMachine makes HotSpot emulate the client VM
     * (CompilerConfig::set_client_emulation_mode_flags: C1 only, one compiler thread, 32 MB code cache,
     * and Serial GC through the GC ergonomics), as it does by default on 32-bit Windows. A compilation
     * mode or garbage collector chosen in the user's JVM arguments still takes precedence.
     * <p>
     * <b>Math intrinsics (Java 18+).</b> Port of PojavLauncher's "Fixed 1.20.5+ versions on 32-bit
     * devices": android-openjdk-build-multiarch commit 8cb72d3 ("Fix[arm32]: Disable InlineIntrinsics on
     * arm32 by default"). Since JDK 18 (JDK-8271128) the 32-bit ARM template interpreter has math entries
     * (templateInterpreterGenerator_arm.cpp, generate_math_entry) written for the hard-float calling
     * convention: Math.abs/sqrt leave their result in VFP register D0, and Math.sin/cos/tan/log/log10/
     * exp/pow pass their argument in D0 to the C runtime. Android's armeabi-v7a uses the softfp
     * convention, where doubles travel in core registers, so all of them return garbage while they are
     * interpreted. The pure-Java StrictMath code (FdLibm) relies on Math.abs and then fails with
     * ArrayIndexOutOfBoundsException in FdLibm$RemPio2, so Minecraft 1.20.5+ (Java 21) crashes or renders
     * garbage. Verified on the bundled Java 21 and 25 under qemu-arm with Android's 32-bit libraries.
     * InlineIntrinsics is a "develop" flag that release JVMs refuse, so all the intrinsics that have such
     * an entry are disabled with the diagnostic DisableIntrinsic flag, which the interpreter honours too
     * (TemplateInterpreterGenerator::generate_intrinsic_entry -> vmIntrinsics::is_intrinsic_available).
     * Every name in the list exists since JDK 18; an unknown name would stop the JVM from starting.
     * <p>
     * Both are harmless on runtimes that already contain Pojav's changes.
     *
     * @param runtime  the runtime that is about to be started
     * @param userArgs the JVM arguments collected so far (including the user's custom arguments)
     * @return the extra JVM arguments, or an empty list if the runtime is not a 32-bit ARM runtime
     */
    public static List<String> getArm32FixArgs(Runtime runtime, List<String> userArgs) {
        List<String> args = new ArrayList<>();
        int runtimeArch = runtime.arch != null ? Architecture.archAsInt(runtime.arch) : Tools.DEVICE_ARCHITECTURE;
        if (runtimeArch != Architecture.ARCH_ARM) return args;

        // 32-bit ARM OpenJDK sets ThreadStackSize = 320 KB by default (leaving ~224 KB usable after
        // HotSpot guard/shadow zones), whereas LTW's Mesa GLSL compiler (_mesa_glsl_compile_shader +
        // _mesa_glsl_parse + glcpp_preprocess) allocates >148 KB of C stack on top of Java/JNI frames.
        boolean userChoseStackSize = false;
        for (String arg : userArgs) {
            if (arg.startsWith("-Xss") || arg.contains("ThreadStackSize=")) {
                userChoseStackSize = true;
                break;
            }
        }
        if (!userChoseStackSize) {
            args.add("-Xss1024k");
        }

        // Java 8 for arm32 is already a client VM; the client emulation mode exists since JDK 17.
        boolean userChoseServerClass = false;
        for (String arg : userArgs) {
            if (arg.contains("NeverActAsServerClassMachine") || arg.contains("AlwaysActAsServerClassMachine")) {
                userChoseServerClass = true;
                break;
            }
        }
        if (runtime.javaVersion >= 17 && !userChoseServerClass) {
            args.add("-XX:+NeverActAsServerClassMachine");
        }

        // JDK 8 (aarch32 port) and JDK 17 are not affected: the interpreter math entries appeared in JDK 18.
        if (runtime.javaVersion >= 18) {
            // The unlock option must come before the diagnostic option, or the JVM refuses to start.
            args.add("-XX:+UnlockDiagnosticVMOptions");
            args.add("-XX:DisableIntrinsic=_dabs,_dsqrt,_dsqrt_strict,_dsin,_dcos,_dtan,_dlog,_dlog10,_dexp,_dpow");
        }
        return args;
    }

    /**
     *  Gives an argument list filled with both the user args
     *  and the auto-generated ones (eg. the window resolution).
     * @return A list filled with args.
     */
    public static List<String> getJavaArgs(String runtimeHome, String userArgumentsString) {
        List<String> userArguments = parseJavaArguments(userArgumentsString);
        String resolvFile;
        resolvFile = new File(PathManager.DIR_DATA,"resolv.conf").getAbsolutePath();

        ArrayList<String> overridableArguments = new ArrayList<>(Arrays.asList(
                "-Djava.home=" + runtimeHome,
                "-Djava.io.tmpdir=" + PathManager.DIR_CACHE.getAbsolutePath(),
                "-Djna.boot.library.path=" + DIR_NATIVE_LIB,
                "-Duser.home=" + ProfilePathManager.INSTANCE.getCurrentPath(),
                "-Duser.language=" + System.getProperty("user.language"),
                "-Dos.name=Linux",
                "-Dos.version=Android-" + Build.VERSION.RELEASE,
                "-Dpojav.path.minecraft=" + ProfilePathHome.getGameHome(),
                "-Dpojav.path.private.account=" + PathManager.DIR_ACCOUNT_NEW,
                "-Duser.timezone=" + TimeZone.getDefault().getID(),

                "-Dorg.lwjgl.vulkan.libname=libvulkan.so",
                //LWJGL 3 DEBUG FLAGS
                //"-Dorg.lwjgl.util.Debug=true",
                //"-Dorg.lwjgl.util.DebugFunctions=true",
                //"-Dorg.lwjgl.util.DebugLoader=true",
                // GLFW Stub width height
                "-Dglfwstub.windowWidth=" + Tools.getDisplayFriendlyRes(currentDisplayMetrics.widthPixels, AllSettings.getResolutionRatio().getValue() / 100F),
                "-Dglfwstub.windowHeight=" + Tools.getDisplayFriendlyRes(currentDisplayMetrics.heightPixels, AllSettings.getResolutionRatio().getValue() / 100F),
                "-Dglfwstub.initEgl=false",
                "-Dext.net.resolvPath=" +resolvFile,
                "-Dlog4j2.formatMsgNoLookups=true", //Log4j RCE mitigation

                "-Dnet.minecraft.clientmodname=" + InfoDistributor.LAUNCHER_NAME,
                "-Dfml.earlyprogresswindow=false", //Forge 1.14+ workaround
                "-Dloader.disable_forked_guis=true",
                "-Djdk.lang.Process.launchMechanism=FORK", // Default is POSIX_SPAWN which requires starting jspawnhelper, which doesn't work on Android
                "-Dsodium.checks.issue2561=false"
        ));

        List<String> additionalArguments = new ArrayList<>();
        for (String arg : overridableArguments) {
            String strippedArg = arg.substring(0,arg.indexOf('='));
            boolean add = true;
            for (String uarg : userArguments) {
                if (uarg.startsWith(strippedArg)) {
                    add = false;
                    break;
                }
            }
            if (add)
                additionalArguments.add(arg);
            else
                Logging.i("ArgProcessor","Arg skipped: "+arg);
        }

        //Add all the arguments
        userArguments.addAll(additionalArguments);
        return userArguments;
    }

    /**
     * Parse and separate java arguments in a user friendly fashion
     * It supports multi line and absence of spaces between arguments
     * The function also supports auto-removal of improper arguments, although it may miss some.
     *
     * @param args The un-parsed argument list.
     * @return Parsed args as an ArrayList
     */
    public static ArrayList<String> parseJavaArguments(String args){
        ArrayList<String> parsedArguments = new ArrayList<>(0);
        args = args.trim().replace(" ", "");
        //For each prefixes, we separate args.
        String[] separators = new String[]{"-XX:-","-XX:+", "-XX:","--", "-D", "-X", "-javaagent:", "-verbose"};
        for (String prefix : separators){
            while (true) {
                int start = args.indexOf(prefix);
                if (start == -1) break;
                //Get the end of the current argument by checking the nearest separator
                int end = -1;
                for (String separator: separators) {
                    int tempEnd = args.indexOf(separator, start + prefix.length());
                    if (tempEnd == -1) continue;
                    if (end == -1) {
                        end = tempEnd;
                        continue;
                    }
                    end = Math.min(end, tempEnd);
                }
                //Fallback
                if (end == -1) end = args.length();

                //Extract it
                String parsedSubString = args.substring(start, end);
                args = args.replace(parsedSubString, "");

                //Check if two args aren't bundled together by mistake
                if (parsedSubString.indexOf('=') == parsedSubString.lastIndexOf('=')) {
                    int arraySize = parsedArguments.size();
                    if (arraySize > 0) {
                        String lastString = parsedArguments.get(arraySize - 1);
                        // Looking for list elements
                        if (lastString.charAt(lastString.length() - 1) == ',' ||
                                parsedSubString.contains(",")) {
                            parsedArguments.set(arraySize - 1, lastString + parsedSubString);
                            continue;
                        }
                    }
                    parsedArguments.add(parsedSubString);
                }
                else Logging.w("JAVA ARGS PARSER", "Removed improper arguments: " + parsedSubString);
            }
        }
        return parsedArguments;
    }

    /** Resolve exactly the selected renderer, never an unrelated library on LD_LIBRARY_PATH. */
    public static String resolveRendererFile(String name) {
        RendererPlugin plugin = RendererPluginManager.getSelectedRendererPlugin();
        File file = new File(name);
        if (!file.isAbsolute()) file = new File(plugin == null ? DIR_NATIVE_LIB : plugin.getPath(), name);
        if (!NativeLibraryInfo.matches(file, Tools.DEVICE_ARCHITECTURE))
            throw new IllegalStateException("Missing or wrong-ABI renderer library: " + file.getAbsolutePath());
        return file.getAbsolutePath();
    }

    public static String loadGraphicsLibrary() {
        if (!Renderers.INSTANCE.isCurrentRendererValid()) return null;
        RendererPlugin plugin = RendererPluginManager.getSelectedRendererPlugin();
        return resolveRendererFile(plugin == null ? Renderers.INSTANCE.getCurrentRenderer().getRendererLibrary() : plugin.getGlName());
    }

    /**
     * Remove the argument from the list, if it exists
     * If the argument exists multiple times, they will all be removed.
     * @param argList The argument list to purge
     * @param argStart The argument to purge from the list.
     */
    private static void purgeArg(List<String> argList, String argStart) {
        argList.removeIf(arg -> arg.startsWith(argStart));
    }
    private static final int EGL_OPENGL_ES_BIT = 0x0001;
    private static final int EGL_OPENGL_ES2_BIT = 0x0004;
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x0040;
    @SuppressWarnings("SameParameterValue")
    private static boolean hasExtension(String extensions, String name) {
        int start = extensions.indexOf(name);
        while (start >= 0) {
            // check that we didn't find a prefix of a longer extension name
            int end = start + name.length();
            if (end == extensions.length() || extensions.charAt(end) == ' ') {
                return true;
            }
            start = extensions.indexOf(name, end);
        }
        return false;
    }

    public static int getDetectedVersion() {
        /*
         * Get all the device configurations and check the EGL_RENDERABLE_TYPE attribute
         * to determine the highest ES version supported by any config. The
         * ES3 bit is defined by EGL 1.5 core or EGL_KHR_create_context. Requiring the
         * extension string on an EGL 1.5 driver incorrectly hides LTW on some GLES3 GPUs.
         */
        EGL10 egl = (EGL10) EGLContext.getEGL();
        EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        int[] numConfigs = new int[1];
        int[] eglVersion = new int[2];
        if (egl.eglInitialize(display, eglVersion)) {
            try {
                boolean checkES3 = EglCapabilities.canQueryEs3Configs(eglVersion[0], eglVersion[1],
                        hasExtension(egl.eglQueryString(display, EGL10.EGL_EXTENSIONS), "EGL_KHR_create_context"));
                if (egl.eglGetConfigs(display, null, 0, numConfigs)) {
                    EGLConfig[] configs = new EGLConfig[numConfigs[0]];
                    if (egl.eglGetConfigs(display, configs, numConfigs[0], numConfigs)) {
                        int highestEsVersion = 0;
                        int[] value = new int[1];
                        for (int i = 0; i < numConfigs[0]; i++) {
                            if (egl.eglGetConfigAttrib(display, configs[i],
                                    EGL10.EGL_RENDERABLE_TYPE, value)) {
                                if (checkES3 && ((value[0] & EGL_OPENGL_ES3_BIT_KHR) ==
                                        EGL_OPENGL_ES3_BIT_KHR)) {
                                    if (highestEsVersion < 3) highestEsVersion = 3;
                                } else if ((value[0] & EGL_OPENGL_ES2_BIT) == EGL_OPENGL_ES2_BIT) {
                                    if (highestEsVersion < 2) highestEsVersion = 2;
                                } else if ((value[0] & EGL_OPENGL_ES_BIT) == EGL_OPENGL_ES_BIT) {
                                    if (highestEsVersion < 1) highestEsVersion = 1;
                                }
                            } else {
                                Logging.w("glesDetect", "Getting config attribute with "
                                        + "EGL10#eglGetConfigAttrib failed "
                                        + "(" + i + "/" + numConfigs[0] + "): "
                                        + egl.eglGetError());
                            }
                        }
                        return highestEsVersion;
                    } else {
                        Logging.e("glesDetect", "Getting configs with EGL10#eglGetConfigs failed: "
                                + egl.eglGetError());
                        return -1;
                    }
                } else {
                    Logging.e("glesDetect", "Getting number of configs with EGL10#eglGetConfigs failed: "
                            + egl.eglGetError());
                    return -2;
                }
            } finally {
                egl.eglTerminate(display);
            }
        } else {
            Logging.e("glesDetect", "Couldn't initialize EGL.");
            return -3;
        }
    }
    public static native int chdir(String path);
    public static native boolean dlopen(String libPath);
    public static native void setLdLibraryPath(String ldLibraryPath);
    public static native void setupBridgeWindow(Object surface);
    public static native void releaseBridgeWindow();
    public static native void initializeGameExitHook();
    public static native void setupExitMethod(Context context);
    // Obtain AWT screen pixels to render on Android SurfaceView
    public static native int[] renderAWTScreenFrame(/* Object canvas, int width, int height */);
    static {
        System.loadLibrary("exithook");
        System.loadLibrary("pojavexec");
        System.loadLibrary("pojavexec_awt");
    }
}
