public class BridgeProbe {
    static { System.loadLibrary("bridgeprobe"); }
    private static native boolean probe(boolean sdl);
    public static void main(String[] args) {
        boolean result = probe(Boolean.parseBoolean(args[0]));
        if (result != Boolean.parseBoolean(args[1])) throw new AssertionError("Unexpected bridge result: " + result);
    }
}
