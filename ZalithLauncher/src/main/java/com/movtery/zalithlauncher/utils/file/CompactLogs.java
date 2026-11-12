package com.movtery.zalithlauncher.utils.file;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Fixed 8 KiB working buffer; preserve launch header and latest failure, not all history. */
public final class CompactLogs {
    private CompactLogs() {}
    public static void add(File file, String name, ZipOutputStream zip) throws IOException {
        if (!file.isFile()) return;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            zip.putNextEntry(new ZipEntry(name));
            long length = input.length();
            byte[] buffer = new byte[8192];
            if (length > 320L * 1024) {
                copy(input, zip, buffer, 64L * 1024);
                zip.write("\n[Middle omitted: compact log keeps 64 KiB header + 256 KiB tail]\n".getBytes(StandardCharsets.UTF_8));
                input.seek(length - 256L * 1024);
                copy(input, zip, buffer, 256L * 1024);
            } else copy(input, zip, buffer, length);
            zip.closeEntry();
        }
    }
    private static void copy(RandomAccessFile in, ZipOutputStream out, byte[] buffer, long remaining) throws IOException {
        while (remaining > 0) {
            int count = in.read(buffer, 0, (int)Math.min(buffer.length, remaining));
            if (count < 0) break;
            out.write(buffer, 0, count); remaining -= count;
        }
    }
}
