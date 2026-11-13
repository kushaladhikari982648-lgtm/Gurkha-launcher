package org.lwjgl.sdl;

import org.lwjgl.glfw.CallbackBridge;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.Library;
import org.lwjgl.system.Platform;
import org.lwjgl.system.SharedLibrary;

public final class SDL {
    private static final SharedLibrary SDL;

    static {
        CallbackBridge.initializeSdl();
        SDL = Library.loadNative(SDL.class, "org.lwjgl.sdl", Configuration.SDL_LIBRARY_NAME.get(Platform.mapLibraryNameBundled("SDL3")), true);
    }

    public static SharedLibrary getLibrary() {
        return SDL;
    }

    private SDL() {
        throw new UnsupportedOperationException();
    }
}
