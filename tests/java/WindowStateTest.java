package org.lwjgl.glfw;

public final class WindowStateTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        WindowState window = new WindowState();
        int foreground = WindowState.HOST_VISIBLE | WindowState.HOST_FOCUSED;
        check(!window.visible(foreground), "GLFW_VISIBLE=false creation hint");
        check(!window.focused(foreground), "hidden windows cannot be focused");
        window.show();
        check(window.focused(foreground), "foreground must not trigger inactive FPS limit");
        check(!window.iconified(foreground), "foreground is not minimized");
        check(!window.focused(WindowState.HOST_VISIBLE), "focus loss while still visible");
        check(window.visible(WindowState.HOST_VISIBLE), "focus loss does not hide window");
        check(!window.focused(0) && !window.visible(0) && window.iconified(0), "background");
        check(!window.focused(WindowState.HOST_FOCUSED), "inconsistent lifecycle event ordering");
        check(window.focused(foreground), "resume restores focus without recreating window");
        window.hide();
        check(!window.visible(foreground) && !window.focused(foreground), "hide");
        window.show();
        check(!window.focused(0), "show cannot steal focus from another Android app");
        check(window.focused(foreground), "events received before window creation are retained");
        System.out.println("PASS: GLFW foreground/background/focus/visibility transitions");
    }
}
