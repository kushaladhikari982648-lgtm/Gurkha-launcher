package net.kdt.pojavlaunch.utils;

import net.kdt.pojavlaunch.Architecture;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** Check both ELF class and machine, before feeding a stale or wrong-ABI file to dlopen. */
public final class NativeLibraryInfo {
    private NativeLibraryInfo() {}

    public static boolean matches(File library, int architecture) {
        byte[] header = new byte[20];
        try (DataInputStream input = new DataInputStream(new FileInputStream(library))) {
            input.readFully(header);
        } catch (IOException e) {
            return false;
        }
        int machine;
        switch (architecture) {
            case Architecture.ARCH_ARM: machine = 40; break;
            case Architecture.ARCH_ARM64: machine = 183; break;
            case Architecture.ARCH_X86: machine = 3; break;
            case Architecture.ARCH_X86_64: machine = 62; break;
            default: return false;
        }
        return header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F'
                && header[4] == (Architecture.is64BitArchitecture(architecture) ? 2 : 1)
                && header[5] == 1 // Android ABIs here are little-endian
                && ((header[18] & 0xff) | ((header[19] & 0xff) << 8)) == machine;
    }
}
