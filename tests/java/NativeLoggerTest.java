import net.kdt.pojavlaunch.Logger;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class NativeLoggerTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        try {
            System.load(args[0]);
            Path folder = Paths.get(args[1]);
            try { Logger.begin(folder.resolve("missing/failed.log").toString()); throw new AssertionError(); }
            catch (Throwable expected) { check(expected instanceof java.io.IOException); }
            Path first = folder.resolve("first.log"), second = folder.resolve("second.log");
            Logger.begin(first.toString());
            Logger.appendToLog("DIRECT-BEFORE");
            char[] large = new char[256*1024]; java.util.Arrays.fill(large,'x');
            Logger.appendToLog(new String(large)); // no variable-sized native stack array
            Logger.appendToLog("Session ID is SECRET");
            CountDownLatch seen = new CountDownLatch(1);
            Logger.setLogListener(text -> {
                if(text.contains("PIPE-BEFORE")) { seen.countDown(); throw new IllegalStateException("test UI listener failure"); }
            });
            System.err.println("PIPE-BEFORE");
            check(seen.await(5,TimeUnit.SECONDS));
            Logger.begin(second.toString());
            CountDownLatch after = new CountDownLatch(1);
            Logger.setLogListener(text -> { if(text.contains("PIPE-AFTER")) after.countDown(); });
            Logger.appendToLog("DIRECT-AFTER");
            System.err.println("PIPE-AFTER");
            check(after.await(5,TimeUnit.SECONDS));
            Logger.setLogListener(null);
            String a = new String(Files.readAllBytes(first),StandardCharsets.UTF_8);
            String b = new String(Files.readAllBytes(second),StandardCharsets.UTF_8);
            check(a.contains("DIRECT-BEFORE") && a.contains("PIPE-BEFORE") && !a.contains("SECRET"));
            check(b.contains("DIRECT-AFTER") && b.contains("PIPE-AFTER") && !b.contains("DIRECT-BEFORE"));
            // Native logger is a process-lifetime attached thread, just as on Android.
            System.exit(0);
        } catch(Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
