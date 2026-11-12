package net.kdt.pojavlaunch.utils;

import java.util.List;
import java.util.Map;

/** Pure policy: enforce only launcher-owned properties, before appending the main class. */
public final class NativeLaunchPolicy {
    private NativeLaunchPolicy() {}
    public static void enforceProperties(List<String> vmArgs, Map<String, String> locked) {
        for (Map.Entry<String, String> entry : locked.entrySet()) {
            String key = "-D" + entry.getKey();
            vmArgs.removeIf(arg -> arg.equals(key) || arg.startsWith(key + "="));
            vmArgs.add(key + "=" + entry.getValue());
        }
    }
    /** Explicit API-audited substitutions, not a promise of on-device rendering. */
    public static boolean supportsModern(String version) {
        return "3.4.1".equals(version) || "3.4.3".equals(version);
    }
}
