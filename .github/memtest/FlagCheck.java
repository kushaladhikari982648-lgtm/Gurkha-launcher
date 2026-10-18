import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Temporary check (removed again): exercises allocation, hashing, strings, arrays and GC under the JVM flags the
 * launcher adds, so that interpreted and JIT-compiled code runs with them. Prints OK or throws.
 */
public class FlagCheck {
    static final class Node {
        final int id;
        final String name;
        Node next;
        Node(int id, String name) { this.id = id; this.name = name; }
    }

    public static void main(String[] args) {
        long checksum = 0;
        for (int round = 0; round < 30; round++) {
            Map<String, Node> map = new HashMap<>();
            Node head = null;
            for (int i = 0; i < 20000; i++) {
                Node n = new Node(i, ("name-" + (i % 997)).intern());
                n.next = head;
                head = n;
                map.put("k" + i, n);
            }
            long sum = 0;
            for (Node n = head; n != null; n = n.next) sum += n.id + n.name.length() + System.identityHashCode(n) % 7;
            for (int i = 0; i < 20000; i += 3) {
                Node n = map.get("k" + i);
                if (n == null || n.id != i) throw new AssertionError("map lookup " + i);
            }
            List<long[]> arrays = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                long[] a = new long[1 + (i * 37) % 4096];
                for (int j = 0; j < a.length; j++) a[j] = j ^ i;
                arrays.add(a);
            }
            for (long[] a : arrays) for (long v : a) sum += v;
            Object[] objs = { new Object(), "s", 1, 2L, 3.0, new int[3], new Node(1, "x") };
            for (Object o : objs) sum += o.getClass().getName().length() + o.hashCode() % 3;
            synchronized (map) { sum++; }
            checksum += sum;
            if (round % 10 == 9) System.gc();
        }
        System.out.println("OK " + checksum + " maxMemory=" + (Runtime.getRuntime().maxMemory() >> 20) + "M");
    }
}
