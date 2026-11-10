#include "runtime_bridge.h"
JNIEXPORT jboolean JNICALL Java_BridgeProbe_probe(JNIEnv *env, jclass unused, jboolean sdl) {
    struct gurkha_runtime_bridge bridge;
    bool ok = gurkha_init_runtime_bridge(env, sdl ? "sdl" : "glfw", &bridge);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); ok = false; }
    if (sdl && (bridge.glfw || bridge.keys || bridge.buttons)) ok = false;
    if (bridge.glfw) (*env)->DeleteGlobalRef(env, bridge.glfw);
    return ok ? JNI_TRUE : JNI_FALSE;
}
