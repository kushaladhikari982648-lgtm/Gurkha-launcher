package com.movtery.zalithlauncher.feature.unpack

import com.movtery.zalithlauncher.launch.SdlSupport
import com.movtery.zalithlauncher.R

enum class Components(val component: String, val displayName: String, val summary: Int?, val privateDirectory: Boolean) {
    OTHER_LOGIN("other_login", "authlib-injector", R.string.splash_screen_authlib_injector, false),
    CACIOCAVALLO("caciocavallo", "caciocavallo", R.string.splash_screen_cacio, false),
    CACIOCAVALLO17("caciocavallo17", "caciocavallo 17", R.string.splash_screen_cacio, false),
    LWJGL3("lwjgl3", "LWJGL 3.3.6", R.string.splash_screen_lwjgl, false),
    // LWJGL 3.4.1 for the versions that use SDL3 (Minecraft 26.3+); its natives are unpacked with it
    LWJGL3_SDL(SdlSupport.COMPONENT, "LWJGL 3.4.1 (SDL)", R.string.splash_screen_lwjgl, false),
    COMPONENTS("components", "Launcher Components", R.string.splash_screen_components, true)
}