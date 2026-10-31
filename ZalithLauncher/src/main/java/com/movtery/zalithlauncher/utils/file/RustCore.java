package com.movtery.zalithlauncher.utils.file;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Small optional Rust boundary. Link failures fall back; real I/O errors are never hidden. */
public final class RustCore {
    private static volatile boolean available = load();
    private RustCore() {}
    private static boolean load() {
        try { System.loadLibrary("gurkha_core"); return true; }
        catch (UnsatisfiedLinkError | SecurityException e) { return false; }
    }
    public static boolean isAvailable() { return available; }
    private static native String nativeSha256(String path) throws IOException;
    private static native void nativeAtomicWrite(String path, byte[] data) throws IOException;

    public static String sha256(File file) throws IOException {
        if (available) {
            try {
                String hash = nativeSha256(file.getAbsolutePath());
                if (hash == null) throw new IOException("Native hash returned no result");
                return hash;
            } catch (UnsatisfiedLinkError e) { available = false; }
        }
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable", e); }
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Hashing interrupted");
                int count = input.read(buffer);
                if (count == -1) break;
                digest.update(buffer, 0, count);
            }
        }
        char[] hex = new char[64];
        char[] digits = "0123456789abcdef".toCharArray();
        byte[] bytes = digest.digest();
        for (int i = 0; i < bytes.length; i++) {
            hex[i * 2] = digits[(bytes[i] & 255) >>> 4];
            hex[i * 2 + 1] = digits[bytes[i] & 15];
        }
        return new String(hex);
    }

    /** Replaces a whole small settings/cache file, never truncating the destination first. */
    public static void atomicWrite(File destination, byte[] data) throws IOException {
        if (available) {
            try { nativeAtomicWrite(destination.getAbsolutePath(), data); return; }
            catch (UnsatisfiedLinkError e) { available = false; }
        }
        Path target = destination.getAbsoluteFile().toPath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".gurkha-", ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                output.write(data);
                output.getFD().sync();
            }
            // API 26+ (the launcher's minimum). Do not silently degrade to truncating writes.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
