package com.gurkha.lowmem;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Finds the spawn area ticket in a class file and changes its radius in place (see {@link SpawnAreaAgent}):
 * <pre>
 *   bipush 11                        &lt;- the radius
 *   getstatic   Unit.INSTANCE
 *   invokevirtual add/removeRegionTicket(TicketType, ChunkPos, int, Object)
 * </pre>
 * and, in the method that adds it (MinecraftServer.prepareLevels), {@code sipush 441}, the number of chunks the
 * server waits for ("while (chunkSource.getTickingGenerated() != 441)").
 */
final class ClassPatcher {
    private static final int GETSTATIC = 0xb2, INVOKEVIRTUAL = 0xb6, INVOKEINTERFACE = 0xb9, BIPUSH = 0x10, SIPUSH = 0x11;
    private static final Pattern TICKET_METHOD = Pattern.compile("\\(L[^;]+;L[^;]+;ILjava/lang/Object;\\)V");

    private ClassPatcher() {
    }

    /** @return the patched class, or null if there is nothing to change */
    static byte[] patch(String className, byte[] original, int radius) {
        byte[] b = original.clone();
        if (u4(b, 0) != 0xCAFEBABE) return null;
        int count = u2(b, 8);
        int[] offsets = new int[count];
        String[] utf8 = new String[count];
        int pos = 10;
        for (int i = 1; i < count; i++) {
            offsets[i] = pos;
            int tag = b[pos] & 0xff;
            switch (tag) {
                case 1: {
                    int length = u2(b, pos + 1);
                    utf8[i] = new String(b, pos + 3, length, StandardCharsets.UTF_8);
                    pos += 3 + length;
                    break;
                }
                case 3: case 4: pos += 5; break;                       // Integer, Float
                case 5: case 6: pos += 9; i++; break;                  // Long, Double take two entries
                case 7: case 8: case 16: case 19: case 20: pos += 3; break;
                case 9: case 10: case 11: case 12: case 17: case 18: pos += 5; break;
                case 15: pos += 4; break;                              // MethodHandle
                default: throw new IllegalStateException("unknown constant pool tag " + tag);
            }
        }
        pos += 6; // access flags, this class, super class
        pos += 2 + 2 * u2(b, pos); // interfaces
        pos = skipMembers(b, pos); // fields
        int methods = u2(b, pos);
        pos += 2;
        int patches = 0;
        for (int m = 0; m < methods; m++) {
            String methodName = utf8[u2(b, pos + 2)] + utf8[u2(b, pos + 4)];
            int attributes = u2(b, pos + 6);
            pos += 8;
            for (int a = 0; a < attributes; a++) {
                int length = u4(b, pos + 2);
                if ("Code".equals(utf8[u2(b, pos)])) {
                    patches += patchCode(b, pos + 14, u4(b, pos + 10), offsets, utf8, radius, className + "." + methodName);
                }
                pos += 6 + length;
            }
        }
        return patches > 0 ? b : null;
    }

    private static int patchCode(byte[] b, int start, int length, int[] offsets, String[] utf8, int radius, String where) {
        int patches = 0;
        int previousOp = -1, previousPc = -1, secondOp = -1, secondPc = -1;
        boolean ticket = false;
        for (int pc = 0; pc < length; ) {
            int op = b[start + pc] & 0xff;
            if ((op == INVOKEVIRTUAL || op == INVOKEINTERFACE) && previousOp == GETSTATIC
                    && secondOp == BIPUSH && b[start + secondPc + 1] == SpawnAreaAgent.VANILLA_RADIUS
                    && TICKET_METHOD.matcher(descriptor(b, u2(b, start + pc + 1), offsets, utf8)).matches()) {
                b[start + secondPc + 1] = (byte) radius;
                ticket = true;
                patches++;
                SpawnAreaAgent.log("spawn area ticket radius " + SpawnAreaAgent.VANILLA_RADIUS + " -> " + radius + " in " + where);
            }
            secondOp = previousOp;
            secondPc = previousPc;
            previousOp = op;
            previousPc = pc;
            pc += length(b, start, pc, op);
        }
        if (ticket) {
            int side = 2 * radius - 1; // the chunks with entity ticking: (2 * (radius - 1) + 1)^2, 441 for radius 11
            for (int pc = 0; pc < length; ) {
                int op = b[start + pc] & 0xff;
                if (op == SIPUSH && u2(b, start + pc + 1) == SpawnAreaAgent.VANILLA_WAIT_CHUNKS) {
                    int wait = side * side;
                    b[start + pc + 1] = (byte) (wait >> 8);
                    b[start + pc + 2] = (byte) wait;
                    patches++;
                    SpawnAreaAgent.log("spawn area chunks waited for " + SpawnAreaAgent.VANILLA_WAIT_CHUNKS + " -> " + wait + " in " + where);
                }
                pc += length(b, start, pc, op);
            }
        }
        return patches;
    }

    /** The descriptor of the Methodref / InterfaceMethodref at constant pool index {@code ref}. */
    private static String descriptor(byte[] b, int ref, int[] offsets, String[] utf8) {
        int nameAndType = u2(b, offsets[ref] + 3);
        String descriptor = utf8[u2(b, offsets[nameAndType] + 3)];
        return descriptor == null ? "" : descriptor;
    }

    /** Length of the instruction at {@code pc} (JVM specification, chapter 6). */
    private static int length(byte[] b, int start, int pc, int op) {
        switch (op) {
            case 0x10: case 0x12: case 0x15: case 0x16: case 0x17: case 0x18: case 0x19:
            case 0x36: case 0x37: case 0x38: case 0x39: case 0x3a: case 0xa9: case 0xbc:
                return 2;
            case 0x11: case 0x13: case 0x14: case 0x84: case 0xb2: case 0xb3: case 0xb4: case 0xb5:
            case 0xb6: case 0xb7: case 0xb8: case 0xbb: case 0xbd: case 0xc0: case 0xc1: case 0xc6: case 0xc7:
                return 3;
            case 0xc5:
                return 4;
            case 0xb9: case 0xba: case 0xc8: case 0xc9:
                return 5;
            case 0xc4: // wide
                return (b[start + pc + 1] & 0xff) == 0x84 ? 6 : 4;
            case 0xaa: { // tableswitch
                int p = (pc + 4) & ~3;
                int low = s4(b, start + p + 4), high = s4(b, start + p + 8);
                return p - pc + 12 + 4 * (high - low + 1);
            }
            case 0xab: { // lookupswitch
                int p = (pc + 4) & ~3;
                return p - pc + 8 + 8 * s4(b, start + p + 4);
            }
            default:
                if (op >= 0x99 && op <= 0xa8) return 3; // if*, goto, jsr
                return 1;
        }
    }

    private static int skipMembers(byte[] b, int pos) {
        int count = u2(b, pos);
        pos += 2;
        for (int i = 0; i < count; i++) {
            int attributes = u2(b, pos + 6);
            pos += 8;
            for (int a = 0; a < attributes; a++) pos += 6 + u4(b, pos + 2);
        }
        return pos;
    }

    private static int u2(byte[] b, int pos) {
        return ((b[pos] & 0xff) << 8) | (b[pos + 1] & 0xff);
    }

    private static int u4(byte[] b, int pos) {
        return ((b[pos] & 0xff) << 24) | ((b[pos + 1] & 0xff) << 16) | ((b[pos + 2] & 0xff) << 8) | (b[pos + 3] & 0xff);
    }

    private static int s4(byte[] b, int pos) {
        return u4(b, pos);
    }
}
