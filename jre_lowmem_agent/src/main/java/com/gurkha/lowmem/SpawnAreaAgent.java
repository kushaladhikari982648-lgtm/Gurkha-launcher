package com.gurkha.lowmem;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;

/**
 * Java agent that makes the spawn area of Minecraft 1.14 - 1.20.4 smaller, for launches with little memory.
 * <p>
 * Those versions load a fixed area around the world spawn whenever a world is opened: a chunk ticket of radius 11
 * (21 x 21 chunks kept loaded, generation up to about 39 x 39 chunks), and the server waits until 441 of them are
 * ready. With a 500 MB heap the singleplayer game runs out of memory while doing that (measured in CI: 1.18.2,
 * 1.19.4, 1.20.1 and 1.20.4 fail, the server alone needs about 400 MB for it), although the game itself needs about
 * 330 MB once the world is open. Minecraft 1.20.5 made the spawn area a game rule (spawnChunkRadius) with a default
 * of 2, which is why newer versions fit.
 * <p>
 * The agent changes the ticket radius in MinecraftServer (preparing the spawn area) and ServerLevel (moving the
 * spawn) and the number of chunks waited for, to the same small size as 1.20.5's default. It only overwrites the two
 * constant operands in place (same instruction length), so nothing else in the class changes, and it finds the
 * places by their bytecode pattern, which works with the obfuscated names of vanilla, Fabric's intermediary names and
 * Forge's names alike. Only Minecraft's own classes are looked at. Newer versions don't have the pattern and are left
 * as they are.
 * <p>
 * Usage: -javaagent:lowmem-agent.jar=&lt;radius&gt; (default 3).
 */
public final class SpawnAreaAgent implements ClassFileTransformer {
    static final int VANILLA_RADIUS = 11;
    static final int VANILLA_WAIT_CHUNKS = 441;
    /** The end of the descriptor of add/removeRegionTicket(TicketType, ChunkPos, int, Object), in the constant pool. */
    private static final byte[] MARKER = ";ILjava/lang/Object;)V".getBytes(StandardCharsets.US_ASCII);

    private final int radius;

    private SpawnAreaAgent(int radius) {
        this.radius = radius;
    }

    public static void premain(String args, Instrumentation instrumentation) {
        int radius = 3;
        if (args != null && !args.trim().isEmpty()) {
            try {
                radius = Integer.parseInt(args.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (radius < 2 || radius >= VANILLA_RADIUS) radius = 3;
        instrumentation.addTransformer(new SpawnAreaAgent(radius));
        log("smaller spawn area for Minecraft 1.14 - 1.20.4 (ticket radius " + radius + " instead of " + VANILLA_RADIUS + "), to save memory");
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (classfileBuffer == null || className == null) return null;
        // Minecraft's own classes only: obfuscated (no package) or net/minecraft/** (Fabric and Forge names)
        if (className.indexOf('/') >= 0 && !className.startsWith("net/minecraft/")) return null;
        if (indexOf(classfileBuffer, MARKER) < 0) return null;
        try {
            return ClassPatcher.patch(className, classfileBuffer, radius);
        } catch (Throwable t) {
            log("could not check " + className + ": " + t);
            return null;
        }
    }

    static void log(String message) {
        System.out.println("[Gurkha Launcher] " + message);
    }

    static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
