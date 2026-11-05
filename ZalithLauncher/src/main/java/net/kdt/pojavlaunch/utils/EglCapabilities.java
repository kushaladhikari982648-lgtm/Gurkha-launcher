package net.kdt.pojavlaunch.utils;

/** EGL 1.5 promoted KHR_create_context to core; its extension name need not be advertised. */
public final class EglCapabilities {
    private EglCapabilities() {}
    public static boolean canQueryEs3Configs(int major, int minor, boolean khrCreateContext) {
        return major > 1 || (major == 1 && minor >= 5) || khrCreateContext;
    }
}
