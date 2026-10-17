package org.lwjgl.glfw;

/**
 * JRE side of the launcher bridge for Minecraft versions that use SDL3 instead of GLFW (26.3 and newer).
 * The GLFW stub, which normally loads libpojavexec, is not on the classpath of those versions, so this
 * class loads it itself. The natives are implemented in libpojavexec.so (input_bridge_v3.c).
 */
public class CallbackBridge {
    // Notification types and actions, see notifyLauncher() in the launcher's org.lwjgl.glfw.CallbackBridge
    public static final int SDL = 0;
    public static final int INIT = 0;

    static {
        try {
            System.loadLibrary("pojavexec");
        } catch (UnsatisfiedLinkError e) {
            System.out.println("CallbackBridge: failed to load libpojavexec: " + e);
        }
    }

    /** Tells the launcher whether the game captured the mouse (relative mouse mode). */
    public static native void nativeSetGrabbing(boolean grab);

    /** Notifies the launcher, for example before SDL is initialized. */
    public static native boolean nativeNotifyLauncher(int type, int... action);
}
