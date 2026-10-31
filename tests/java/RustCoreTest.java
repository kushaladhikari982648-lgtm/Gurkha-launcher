import com.movtery.zalithlauncher.utils.file.RustCore;
import com.movtery.zalithlauncher.renderer.RendererSelection;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public final class RustCoreTest {
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static String reference(byte[] data) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) out.append(String.format("%02x", b & 255));
        return out.toString();
    }
    public static void main(String[] args) throws Exception {
        check(RustCore.isAvailable() == args[0].equals("native"), "expected native/fallback mode");
        Path dir = Files.createTempDirectory("gurkha-core-test");
        try {
            File file = dir.resolve("नेपाली-🌲.json").toFile();
            for (int length : new int[]{0, 3, 65535, 65536, 65537, 1048579, 64 * 1024 * 1024 + 17}) {
                byte[] bytes = new byte[length];
                new Random(42).nextBytes(bytes);
                RustCore.atomicWrite(file, bytes);
                check(Arrays.equals(bytes, Files.readAllBytes(file.toPath())), "replacement contents");
                check(RustCore.sha256(file).equals(reference(bytes)), "SHA-256 at chunk boundary " + length);
            }
            Thread.currentThread().interrupt();
            try { RustCore.sha256(file); throw new AssertionError("cancellation ignored"); }
            catch (InterruptedIOException expected) { check(Thread.currentThread().isInterrupted(), "interrupt preserved"); }
            finally { Thread.interrupted(); }
            try { RustCore.sha256(dir.resolve("missing").toFile()); throw new AssertionError("missing file accepted"); }
            catch (IOException expected) { }
            try { RustCore.sha256(dir.toFile()); throw new AssertionError("directory accepted"); }
            catch (IOException expected) { }
            Path targetDirectory = Files.createDirectory(dir.resolve("keep-directory"));
            try { RustCore.atomicWrite(targetDirectory.toFile(), new byte[]{1}); throw new AssertionError("directory overwritten"); }
            catch (IOException expected) { check(Files.isDirectory(targetDirectory), "destination preserved on failure"); }
            try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                check(files.noneMatch(p -> p.getFileName().toString().startsWith(".gurkha-")), "temporary files cleaned up");
            }
            ExecutorService workers = Executors.newFixedThreadPool(4);
            List<Future<?>> writes = new ArrayList<>();
            Set<String> complete = new HashSet<>();
            try {
                for (int i = 0; i < 20; i++) {
                    String value = "{\"save\":" + i + "}";
                    complete.add(value);
                    writes.add(workers.submit(() -> { RustCore.atomicWrite(file, value.getBytes(StandardCharsets.UTF_8)); return null; }));
                }
                for (Future<?> write : writes) write.get();
                check(complete.contains(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)), "concurrent writes remain whole files");
            } finally { workers.shutdownNow(); }
            check(RendererSelection.normalize("gallium_freedreno").equals("8b52d82d-8f6d-4d3a-a767-dc93f8b72fc7"), "old renderer ID migrated");
            check(RendererSelection.isRemoved("1ad7249f-5784-4f00-bc72-174b3578ee46"), "old renderer UUID retired");
            check(RendererSelection.normalize("opengles3_rust").equals("opengles3_rust"), "RustGL choice unchanged");
            System.out.println("PASS: " + args[0] + " hashing, Unicode, cancellation, atomic replacement, concurrent writes, renderer migration");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException e) { throw new UncheckedIOException(e); } });
            }
        }
    }
}
