package org.lwjgl.system.linux;

import java.nio.ByteBuffer;
import javax.annotation.Nullable;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.NativeType;

import static org.lwjgl.system.Checks.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;

public class DynamicLinkLoader {

    public static final int
        RTLD_LAZY         = 0x1,
        RTLD_NOW          = 0x2,
        RTLD_BINDING_MASK = 0x3,
        RTLD_NOLOAD       = 0x4,
        RTLD_DEEPBIND     = 0x8;

    public static final int
        RTLD_GLOBAL   = 0x100,
        RTLD_LOCAL    = 0,
        RTLD_NODELETE = 0x1000;

    static {
        Library.initialize();
        try {
            System.loadLibrary("pojavexec");
        } catch (Throwable ignored) {
        }
    }

    protected DynamicLinkLoader() {
        throw new UnsupportedOperationException();
    }

    public static native long ndlopen(long filename, int mode);

    @NativeType("void *")
    public static long dlopen(@Nullable @NativeType("char const *") ByteBuffer filename, int mode) {
        if (CHECKS) {
            checkNT1Safe(filename);
        }
        return ndlopen(memAddressSafe(filename), mode);
    }

    @NativeType("void *")
    public static long dlopen(@Nullable @NativeType("char const *") CharSequence filename, int mode) {
        MemoryStack stack = stackGet();
        int stackPointer = stack.getPointer();
        try {
            stack.nUTF8Safe(filename, true);
            long filenameEncoded = filename == null ? NULL : stack.getPointerAddress();
            return ndlopen(filenameEncoded, mode);
        } finally {
            stack.setPointer(stackPointer);
        }
    }

    public static native long ndlerror();

    @Nullable
    @NativeType("char *")
    public static String dlerror() {
        long __result = ndlerror();
        return memUTF8Safe(__result);
    }

    public static native long ndlsym(long handle, long name);

    @NativeType("void *")
    public static long dlsym(@NativeType("void *") long handle, @NativeType("char const *") ByteBuffer name) {
        if (CHECKS) {
            check(handle);
            checkNT1(name);
        }
        return ndlsym(handle, memAddress(name));
    }

    @NativeType("void *")
    public static long dlsym(@NativeType("void *") long handle, @NativeType("char const *") CharSequence name) {
        if (CHECKS) {
            check(handle);
        }
        MemoryStack stack = stackGet();
        int stackPointer = stack.getPointer();
        try {
            stack.nASCII(name, true);
            long nameEncoded = stack.getPointerAddress();
            return ndlsym(handle, nameEncoded);
        } finally {
            stack.setPointer(stackPointer);
        }
    }

    public static native int ndlclose(long handle);

    public static int dlclose(@NativeType("void *") long handle) {
        if (CHECKS) {
            check(handle);
        }
        return ndlclose(handle);
    }
}
