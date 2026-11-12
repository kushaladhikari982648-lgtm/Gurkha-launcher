package net.kdt.pojavlaunch.utils;

import java.io.File;

/** Resolve only within the selected runtime; never borrow another VM from the search path. */
public final class RuntimeLibraryPaths {
    private RuntimeLibraryPaths() {}
    public static File jli(File libDir) {
        return first("JLI", new File(libDir, "libjli.so"), new File(libDir, "jli/libjli.so"));
    }
    public static File jvm(File libDir) {
        return first("JVM", new File(libDir, "server/libjvm.so"), new File(libDir, "client/libjvm.so"));
    }
    private static File first(String role, File preferred, File fallback) {
        if (preferred.isFile()) return preferred.getAbsoluteFile();
        if (fallback.isFile()) return fallback.getAbsoluteFile();
        throw new IllegalStateException("Incomplete runtime: missing " + role + "; checked "
                + preferred + " and " + fallback + ". Reinstall the selected Java runtime.");
    }
}
