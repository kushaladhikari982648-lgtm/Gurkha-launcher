package org.lwjgl.glfw;

import org.libsdl.app.SDLActivity;
import net.kdt.pojavlaunch.MinecraftGLSurface;
import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.EfficientAndroidLWJGLKeycode;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.content.ClipData;
import android.content.ClipDescription;
import android.view.Choreographer;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.GrabListener;
import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.MainActivity;

import java.util.ArrayList;

import dalvik.annotation.optimization.CriticalNative;

public class CallbackBridge {
    public static final Choreographer sChoreographer = Choreographer.getInstance();
    private static boolean isGrabbing = false;
    private static final ArrayList<GrabListener> grabListeners = new ArrayList<>();
    
    public static final int CLIPBOARD_COPY = 2000;
    public static final int CLIPBOARD_PASTE = 2001;
    public static final int CLIPBOARD_OPEN = 2002;
    
    public static volatile int windowWidth, windowHeight;
    public static volatile int physicalWidth, physicalHeight;
    public static float mouseX, mouseY;
    // SDL (Minecraft 26.3+): last cursor position passed to sendCursorPos (for relative motion while the
    // mouse is grabbed) and the state of the mouse buttons as Android MotionEvent button flags
    private static float sdlLastX, sdlLastY;
    private static int sdlMouseButtonState = 0;
    public volatile static boolean holdingAlt, holdingCapslock, holdingCtrl,
            holdingNumlock, holdingShift;

    public static void putMouseEventWithCoords(int button, float x, float y) {
        putMouseEventWithCoords(button, true, x, y);
        sChoreographer.postFrameCallbackDelayed(l -> putMouseEventWithCoords(button, false, x, y), 33);
    }
    
    public static void putMouseEventWithCoords(int button, boolean isDown, float x, float y /* , int dz, long nanos */) {
        sendCursorPos(x, y);
        sendMouseKeycode(button, CallbackBridge.getCurrentMods(), isDown);
    }


    public static void sendCursorPos(float x, float y) {
        mouseX = x;
        mouseY = y;
        nativeSendCursorPos(mouseX, mouseY);
        float deltaX = x - sdlLastX, deltaY = y - sdlLastY;
        sdlLastX = x;
        sdlLastY = y;
        if (!MinecraftGLSurface.sdlEnabled) return;
        // SDL treats HOVER_MOVE and MOVE the same; while the game grabs the mouse it expects relative motion
        if (isGrabbing) SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, deltaX, deltaY, true);
        else SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, x, y, false);
    }

    public static void sendKeycode(int keycode, char keychar, int scancode, int modifiers, boolean isDown) {
        // TODO CHECK: This may cause input issue, not receive input!
        if(keycode != 0)  nativeSendKey(keycode,scancode,isDown ? 1 : 0, modifiers);
        if(isDown && keychar != '\u0000') {
            nativeSendCharMods(keychar,modifiers);
            nativeSendChar(keychar);
        }
        if (!MinecraftGLSurface.sdlEnabled || keycode == 0) return;
        int androidKeycode = EfficientAndroidLWJGLKeycode.getAndroidKeycode(keycode);
        if (androidKeycode == KeyEvent.KEYCODE_UNKNOWN) return;
        if (isDown) SDLActivity.onNativeKeyDown(androidKeycode);
        else SDLActivity.onNativeKeyUp(androidKeycode);
    }

    public static void sendChar(char keychar, int modifiers){
        nativeSendCharMods(keychar,modifiers);
        nativeSendChar(keychar);
        if (!MinecraftGLSurface.sdlEnabled) return;
        // Normally SDL gets text through its own input connection (TouchCharInput switches to it)
        int androidKeycode = EfficientAndroidLWJGLKeycode.getAndroidKeycode(keychar);
        if (androidKeycode == KeyEvent.KEYCODE_UNKNOWN) return;
        SDLActivity.onNativeKeyDown(androidKeycode);
        SDLActivity.onNativeKeyUp(androidKeycode);
    }

    public static void sendKeyPress(int keyCode, int modifiers, boolean status) {
        sendKeyPress(keyCode, 0, modifiers, status);
    }

    public static void sendKeyPress(int keyCode, int scancode, int modifiers, boolean status) {
        sendKeyPress(keyCode, '\u0000', scancode, modifiers, status);
    }

    public static void sendKeyPress(int keyCode, char keyChar, int scancode, int modifiers, boolean status) {
        CallbackBridge.sendKeycode(keyCode, keyChar, scancode, modifiers, status);
    }

    public static void sendKeyPress(int keyCode) {
        sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), true);
        sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), false);
    }

    public static void sendMouseButton(int button, boolean status) {
        CallbackBridge.sendMouseKeycode(button, CallbackBridge.getCurrentMods(), status);
    }

    public static void sendMouseKeycode(int button, int modifiers, boolean isDown) {
        // if (isGrabbing()) DEBUG_STRING.append("MouseGrabStrace: " + android.util.Log.getStackTraceString(new Throwable()) + "\n");
        nativeSendMouseButton(button, isDown ? 1 : 0, modifiers);
        if (!MinecraftGLSurface.sdlEnabled) return;
        int androidButton;
        switch (button) {
            case LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT: androidButton = MotionEvent.BUTTON_PRIMARY; break;
            case LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT: androidButton = MotionEvent.BUTTON_SECONDARY; break;
            case LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE: androidButton = MotionEvent.BUTTON_TERTIARY; break;
            // Back and forward are swapped on SDL's Android side (same as Amethyst)
            case LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_4: androidButton = MotionEvent.BUTTON_FORWARD; break;
            case LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_5: androidButton = MotionEvent.BUTTON_BACK; break;
            default: return;
        }
        // SDL expects MotionEvent.getButtonState(), i.e. the buttons still held after this event
        if (isDown) sdlMouseButtonState |= androidButton;
        else sdlMouseButtonState &= ~androidButton;
        SDLActivity.onNativeMouse(sdlMouseButtonState, isDown ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP, mouseX, mouseY, false);
    }

    public static void sendMouseKeycode(int keycode) {
        sendMouseKeycode(keycode, CallbackBridge.getCurrentMods(), true);
        sendMouseKeycode(keycode, CallbackBridge.getCurrentMods(), false);
    }
    
    public static void sendScroll(double xoffset, double yoffset) {
        nativeSendScroll(xoffset, yoffset);
        if (!MinecraftGLSurface.sdlEnabled) return;
        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_SCROLL, (float) xoffset, (float) yoffset, false);
    }

    public static void sendUpdateWindowSize(int w, int h) {
        nativeSendScreenSize(w, h);
    }

    public static boolean isGrabbing() {
        // Avoid going through the JNI each time.
        return isGrabbing;
    }

    // Notification types and actions, sent from the JRE side (lwjgl3-sdl component, jre_lwjgl3sdl)
    public static final int NOTIF_TYPE_SDL = 0;
    public static final int ACTION_INIT_LAUNCHER_INTEGRATION = 0;

    /**
     * Called from the JRE side through nativeNotifyLauncher (input_bridge_v3.c), for example right before
     * the game calls SDL_Init: loads SDL in the launcher's VM and hands it the game surface and input.
     * Ported from Amethyst-Android.
     * @return whether the notification was handled
     */
    @Keep
    @SuppressWarnings("unused")
    public static boolean notifyLauncher(int type, int... action) {
        if (type != NOTIF_TYPE_SDL || action.length == 0 || action[0] != ACTION_INIT_LAUNCHER_INTEGRATION) return false;
        if (MinecraftGLSurface.sdlEnabled) return true;
        try {
            // Loaded here as well: SDL's JNI_OnLoad must run in the launcher's VM to register its Java glue
            System.loadLibrary("SDL3");
            System.loadLibrary("SDL2");
            org.libsdl.app.SDL.setupJNI();
            MinecraftGLSurface.sdlEnabled = true;
            if (SDLActivity.getSDLSurface() != null) {
                // SDL needs the size of the native surface to map input coordinates
                SDLActivity.getSDLSurface().nativeResize(windowWidth, windowHeight);
            }
            Logger.appendToLog("Info: SDL launcher integration enabled");
            return true;
        } catch (Throwable e) {
            Logger.appendToLog("Error: failed to initialize the SDL launcher integration, the game will likely crash: " + e);
            return false;
        }
    }

    // Called from JRE side
    @SuppressWarnings("unused")
    public static @Nullable String accessAndroidClipboard(int type, String copy) {
        switch (type) {
            case CLIPBOARD_COPY:
                MainActivity.GLOBAL_CLIPBOARD.setPrimaryClip(ClipData.newPlainText("Copy", copy));
                return null;

            case CLIPBOARD_PASTE:
                if (MainActivity.GLOBAL_CLIPBOARD.hasPrimaryClip() && MainActivity.GLOBAL_CLIPBOARD.getPrimaryClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
                    return MainActivity.GLOBAL_CLIPBOARD.getPrimaryClip().getItemAt(0).getText().toString();
                } else {
                    return "";
                }

            case CLIPBOARD_OPEN:
                MainActivity.openLink(copy);
                return null;
            default: return null;
        }
    }


    public static int getCurrentMods() {
        int currMods = 0;
        if (holdingAlt) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_ALT;
        } if (holdingCapslock) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_CAPS_LOCK;
        } if (holdingCtrl) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_CONTROL;
        } if (holdingNumlock) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_NUM_LOCK;
        } if (holdingShift) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_SHIFT;
        }
        return currMods;
    }

    public static void setModifiers(int keyCode, boolean isDown){
        switch (keyCode){
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT:
                CallbackBridge.holdingShift = isDown;
                return;

            case LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL:
                CallbackBridge.holdingCtrl = isDown;
                return;

            case LwjglGlfwKeycode.GLFW_KEY_LEFT_ALT:
                CallbackBridge.holdingAlt = isDown;
                return;

            case LwjglGlfwKeycode.GLFW_KEY_CAPS_LOCK:
                CallbackBridge.holdingCapslock = isDown;
                return;

            case LwjglGlfwKeycode.GLFW_KEY_NUM_LOCK:
                CallbackBridge.holdingNumlock = isDown;
        }
    }

    //Called from JRE side
    @SuppressWarnings("unused")
    private static void onGrabStateChanged(final boolean grabbing) {
        isGrabbing = grabbing;
        sChoreographer.postFrameCallbackDelayed((time) -> {
            // If the grab re-changed, skip notify process
            if(isGrabbing != grabbing) return;

            System.out.println("Grab changed : " + grabbing);
            synchronized (grabListeners) {
                for (GrabListener g : grabListeners) g.onGrabState(grabbing);
            }

        }, 16);

    }
    public static void addGrabListener(GrabListener listener) {
        synchronized (grabListeners) {
            listener.onGrabState(isGrabbing);
            grabListeners.add(listener);
        }
    }
    public static void removeGrabListener(GrabListener listener) {
        synchronized (grabListeners) {
            grabListeners.remove(listener);
        }
    }

    @Keep @CriticalNative public static native void nativeSetUseInputStackQueue(boolean useInputStackQueue);

    @Keep @CriticalNative private static native boolean nativeSendChar(char codepoint);
    // GLFW: GLFWCharModsCallback deprecated, but is Minecraft still use?
    @Keep @CriticalNative private static native boolean nativeSendCharMods(char codepoint, int mods);
    @Keep @CriticalNative private static native void nativeSendKey(int key, int scancode, int action, int mods);
    // private static native void nativeSendCursorEnter(int entered);
    @Keep @CriticalNative private static native void nativeSendCursorPos(float x, float y);
    @Keep @CriticalNative private static native void nativeSendMouseButton(int button, int action, int mods);
    @Keep @CriticalNative private static native void nativeSendScroll(double xoffset, double yoffset);
    @Keep @CriticalNative private static native void nativeSendScreenSize(int width, int height);
    @Keep public static native void nativeSetWindowAttrib(int attrib, int value);
    @Keep public static native int getCurrentFps();

    static {
        System.loadLibrary("pojavexec");
    }
}

