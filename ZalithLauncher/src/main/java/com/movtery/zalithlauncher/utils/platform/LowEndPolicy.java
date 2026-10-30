package com.movtery.zalithlauncher.utils.platform;

import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Pure policy, independently tested without Android or a particular JVM/GPU driver.
 * These are conservative resource budgets, not benchmark-derived FPS claims.
 */
public final class LowEndPolicy {
    private LowEndPolicy() {}

    /** RAM heuristic plus the MT6761 family (Helio A22, including 4 GB models).
     * Unknown hardware is not treated as low-end merely because it has four cores.
     */
    public static boolean defaultEnabled(int totalRamMb, String... hardwareNames) {
        if (totalRamMb > 0 && totalRamMb < 3072) return true;
        for (String hardware : hardwareNames) {
            if (hardware == null) continue;
            String name = hardware.toLowerCase(Locale.ROOT);
            if (name.contains("mt6761") || name.contains("helio a22")) return true;
        }
        return false;
    }

    /** Keep small/common heaps fixed to avoid avoidable early heap-growth collections.
     * Larger allocations can still grow up to the user's chosen Xmx.
     * Mode off preserves the previous fixed-size-heap policy. Never emits Xms > Xmx.
     */
    public static int initialHeapMb(int maximumMb, boolean enabled) {
        if (maximumMb <= 0) throw new IllegalArgumentException("Heap allocation must be positive");
        return enabled ? Math.min(maximumMb, 512) : maximumMb;
    }

    /** Do not rewrite the saved slider value; the profile only limits effective concurrency. */
    public static int downloadWorkers(int requested, int taskCount, boolean enabled) {
        int ceiling = enabled ? 4 : 64;
        int configured = Math.max(1, Math.min(requested, ceiling));
        return Math.min(configured, Math.max(1, taskCount));
    }

    /** Includes the zero-work case: a capacity-0 queue / maxPoolSize=0 would throw. */
    public static ThreadPoolExecutor gameDownloadPool(int requested, int taskCount, boolean enabled) {
        int workers = downloadWorkers(requested, taskCount, enabled);
        return new ThreadPoolExecutor(Math.max(1, workers / 2), workers, 500,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(Math.max(1, taskCount)));
    }

    /** Hashing and decompressing jars is CPU/storage-heavy, not unbounded network I/O. */
    public static int modScanWorkers(int processors, int fileCount, boolean enabled) {
        int limit = Math.min(Math.max(1, processors), enabled ? 2 : 8);
        return Math.min(limit, Math.max(1, fileCount));
    }

    /** Five progress updates/second are sufficient while downloading on a small CPU. */
    public static int progressIntervalMs(int normalMs, boolean enabled) {
        return enabled ? Math.max(normalMs, 200) : normalMs;
    }

    /** Used ONLY when copying the launcher's asset to a previously absent options.txt.
     * Do not run this over an existing game profile: it represents user preferences.
     */
    public static String newGameDefaults(String template, boolean enabled) {
        if (!enabled) return template;
        StringBuilder out = new StringBuilder(template.length());
        for (String line : template.split("\n", -1)) {
            if (line.startsWith("clouds:")) line = "clouds:false";
            else if (line.startsWith("renderClouds:")) line = "renderClouds:\"false\"";
            out.append(line).append('\n');
        }
        // Retain the template's trailing-newline convention.
        out.setLength(out.length() - 1);
        return out.toString();
    }
}
