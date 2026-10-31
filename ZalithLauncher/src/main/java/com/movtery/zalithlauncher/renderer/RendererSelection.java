package com.movtery.zalithlauncher.renderer;

/** Compatibility for saved profiles after retiring the standalone Freedreno renderer. */
public final class RendererSelection {
    private RendererSelection() {}
    public static boolean isRemoved(String value) {
        return "gallium_freedreno".equals(value)
                || "1ad7249f-5784-4f00-bc72-174b3578ee46".equals(value);
    }
    public static String normalize(String value) {
        // Use the existing conservative GLES fallback, not a Vulkan path or silent settings rewrite.
        return isRemoved(value) ? "8b52d82d-8f6d-4d3a-a767-dc93f8b72fc7" : value;
    }
}
