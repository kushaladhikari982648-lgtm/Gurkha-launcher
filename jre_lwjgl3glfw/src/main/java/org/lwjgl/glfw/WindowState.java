package org.lwjgl.glfw;

/** Window-local visibility combined with Android lifecycle state, independent of a GL backend. */
final class WindowState {
    // Must match WINDOW_STATE_* in input_bridge_v3.c.
    static final int HOST_FOCUSED = 1;
    static final int HOST_VISIBLE = 2;
    private boolean shown;

    void show() { shown = true; }
    void hide() { shown = false; }
    boolean visible(int host) { return shown && (host & HOST_VISIBLE) != 0; }
    boolean focused(int host) { return visible(host) && (host & HOST_FOCUSED) != 0; }
    boolean iconified(int host) { return (host & HOST_VISIBLE) == 0; }
}
