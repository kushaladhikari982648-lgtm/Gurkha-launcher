package net.kdt.pojavlaunch.multirt;

import net.kdt.pojavlaunch.Architecture;
import java.util.List;

/** Shared by game launch, runtime auto-pick and Java installers. No Android UI dependencies. */
public final class RuntimeSelection {
    private RuntimeSelection() {}

    public static int majorVersion(String version) {
        if (version == null) return 0;
        String[] parts = version.split("[.+_-]");
        try {
            return Integer.parseInt(parts[0].equals("1") ? parts[1] : parts[0]);
        } catch (NumberFormatException | IndexOutOfBoundsException ignored) {
            return 0;
        }
    }

    public static boolean compatible(Runtime runtime, int minimumJava, int processArch) {
        return runtime != null && runtime.versionString != null && runtime.javaVersion >= minimumJava
                && processArch != Architecture.UNSUPPORTED_ARCH
                && Architecture.archAsInt(runtime.arch) == processArch;
    }

    public static Runtime select(List<Runtime> installed, int minimumJava, int processArch, String preferred) {
        Runtime nearest = null;
        for (Runtime candidate : installed) {
            if (!compatible(candidate, minimumJava, processArch)) continue;
            if (candidate.name.equals(preferred)) return candidate;
            if (nearest == null || candidate.javaVersion < nearest.javaVersion) nearest = candidate;
        }
        return nearest;
    }
}
