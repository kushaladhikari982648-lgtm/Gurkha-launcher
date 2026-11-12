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
    /** Drop manifest classpaths: the launcher appends one patched Android classpath. */
    public static String[] manifestVmArgs(List<String> args, Map<String, String> values) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("-cp") || arg.equals("-classpath") || arg.equals("--class-path")) {
                if (++i >= args.size()) throw new IllegalArgumentException("Manifest classpath has no value");
                continue;
            }
            if (arg.startsWith("--class-path=")) continue;
            for (Map.Entry<String, String> entry : values.entrySet())
                arg = arg.replace("${" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
            result.add(arg);
        }
        return result.toArray(new String[0]);
    }

    /** Explicit API-audited substitutions, not a promise of on-device rendering. */
    public static boolean supportsModern(String version) {
        return "3.4.1".equals(version) || "3.4.3".equals(version);
    }
}
