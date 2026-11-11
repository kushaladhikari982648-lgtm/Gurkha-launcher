package com.movtery.zalithlauncher.feature.media;

import java.util.Locale;

/** Small, testable limits for memory-constrained phones. */
public final class MediaPolicy {
    private MediaPolicy() {}
    public static boolean isVideo(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp4") || n.endsWith(".m4v") || n.endsWith(".webm")
                || n.endsWith(".mkv") || n.endsWith(".3gp") || n.endsWith(".mov");
    }
    public static boolean validTexture(int width, int height, boolean cape) {
        if (width < 64 || width > 1024 || (width & (width - 1)) != 0) return false;
        return cape ? height == width / 2 : height == width || height == width / 2;
    }
    public static boolean validPlayerName(String name) { return name.matches("[A-Za-z0-9_]{1,16}"); }
    /** 480p / at most 854 wide, even dimensions for AVC. Never upscale. */
    public static int[] recordingSize(int width, int height) {
        if (width < 2 || height < 2) throw new IllegalArgumentException("Game surface is not ready");
        double scale = Math.min(1.0, Math.min(854.0 / width, 480.0 / height));
        return new int[]{Math.max(2, ((int)(width * scale)) & ~1), Math.max(2, ((int)(height * scale)) & ~1)};
    }
}
