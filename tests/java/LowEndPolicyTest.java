import com.movtery.zalithlauncher.utils.platform.LowEndPolicy;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class LowEndPolicyTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        check(LowEndPolicy.defaultEnabled(1900, "unknown"), "2 GB device");
        check(LowEndPolicy.defaultEnabled(2900, "unknown"), "3 GB marketed device");
        check(LowEndPolicy.defaultEnabled(4096, "MT6761V/WBB"), "4 GB Helio A22");
        check(LowEndPolicy.defaultEnabled(4096, "MediaTek Helio A22"), "SoC model");
        check(!LowEndPolicy.defaultEnabled(0, "unknown", null), "unknown must not mean low-end");
        check(!LowEndPolicy.defaultEnabled(8192, "sm8650"), "high-end opt-in only");
        check(!LowEndPolicy.defaultEnabled(3072, "unknown"), "RAM boundary");

        for (int max : new int[]{1, 128, 256, 500, 1024, 1536, 4096}) {
            int initial = LowEndPolicy.initialHeapMb(max, true);
            check(initial > 0 && initial <= max && initial <= 512, "bounded initial heap");
            check(LowEndPolicy.initialHeapMb(max, false) == max, "mode-off fixed heap");
        }
        check(LowEndPolicy.initialHeapMb(500, true) == 500, "500 MB heap avoids forced growth");
        check(LowEndPolicy.initialHeapMb(1024, true) == 512, "larger heaps retain a bounded initial commitment");
        try {
            LowEndPolicy.initialHeapMb(0, true);
            throw new AssertionError("zero heap must be rejected");
        } catch (IllegalArgumentException expected) { }

        for (int requested : new int[]{-1, 0, 1, 2, 4, 64, Integer.MAX_VALUE}) {
            for (int jobs : new int[]{0, 1, 3, 100}) {
                int effective = LowEndPolicy.downloadWorkers(requested, jobs, true);
                check(effective >= 1 && effective <= 4, "safe download budget");
                check(effective <= Math.max(1, jobs), "no excess workers for tiny task list");
            }
        }
        check(LowEndPolicy.downloadWorkers(64, 100, false) == 64, "saved setting restored when disabled");
        check(LowEndPolicy.downloadWorkers(1, 100, true) == 1, "lower manual budget retained");
        check(LowEndPolicy.modScanWorkers(4, 200, true) == 2, "A22 jar workers");
        check(LowEndPolicy.modScanWorkers(1, 200, true) == 1, "single-core budget");
        check(LowEndPolicy.modScanWorkers(16, 200, false) == 8, "normal scan remains bounded");
        check(LowEndPolicy.modScanWorkers(4, 1, true) == 1, "single jar");
        check(LowEndPolicy.progressIntervalMs(20, true) == 200, "low-end progress throttling");
        check(LowEndPolicy.progressIntervalMs(33, false) == 33, "normal progress unchanged");

        // Actual executor: no work must not throw; queued work must all execute, not be discarded.
        for (int jobs : new int[]{0, 1, 100}) {
            ThreadPoolExecutor pool = LowEndPolicy.gameDownloadPool(64, jobs, true);
            AtomicInteger completed = new AtomicInteger();
            try {
                check(pool.getMaximumPoolSize() <= 4, "executor budget matches policy");
                for (int i = 0; i < jobs; i++) pool.execute(completed::incrementAndGet);
                pool.shutdown();
                check(pool.awaitTermination(5, TimeUnit.SECONDS), "executor shuts down");
                check(completed.get() == jobs, "every queued download runs");
            } finally { pool.shutdownNow(); }
        }

        String template = "clouds:true\nrenderClouds:\"fast\"\nmaxFps:260\nrenderDistance:4\n";
        check(LowEndPolicy.newGameDefaults(template, false).equals(template), "opt-out template unchanged");
        String light = LowEndPolicy.newGameDefaults(template, true);
        check(light.contains("clouds:false\n") && light.contains("renderClouds:\"false\"\n"), "cloud formats");
        check(light.contains("maxFps:260\n") && light.contains("renderDistance:4\n"), "no FPS/render distance override");
        check(LowEndPolicy.newGameDefaults(light, true).equals(light), "idempotent defaults");
        System.out.println("PASS: low-end detection, heap, worker budgets, real executors and new-profile defaults");
    }
}
