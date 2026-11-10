#ifndef GURKHA_RUNTIME_BRIDGE_H
#define GURKHA_RUNTIME_BRIDGE_H
#include <jni.h>
#include <stdbool.h>
#include <string.h>

/* Separate the window backends, as MojoLauncher does. SDL's classpath has no
 * launcher GLFW shim: looking up its private methods/fields is a JNI crash, not
 * an optional feature probe. No Android dependencies so host JNI tests cover it. */
struct gurkha_runtime_bridge {
    jclass glfw;
    jmethodID set_attrib, resize;
    jbyte *keys, *buttons;
};

static bool gurkha_init_runtime_bridge(JNIEnv *env, const char *backend,
                                       struct gurkha_runtime_bridge *bridge) {
    memset(bridge, 0, sizeof(*bridge));
    if (backend && strcmp(backend, "sdl") == 0) return true;
    jclass local = (*env)->FindClass(env, "org/lwjgl/glfw/GLFW");
    jobject keys = NULL, buttons = NULL;
    if (!local) return false;
    bridge->glfw = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (!bridge->glfw) return false;
    bridge->set_attrib = (*env)->GetStaticMethodID(env, bridge->glfw, "glfwSetWindowAttrib", "(JII)V");
    if (!bridge->set_attrib) goto failed;
    bridge->resize = (*env)->GetStaticMethodID(env, bridge->glfw, "internalWindowSizeChanged", "(JII)V");
    if (!bridge->resize) goto failed;
    jfieldID key_field = (*env)->GetStaticFieldID(env, bridge->glfw, "keyDownBuffer", "Ljava/nio/ByteBuffer;");
    if (!key_field) goto failed;
    jfieldID button_field = (*env)->GetStaticFieldID(env, bridge->glfw, "mouseDownBuffer", "Ljava/nio/ByteBuffer;");
    if (!button_field) goto failed;
    keys = (*env)->GetStaticObjectField(env, bridge->glfw, key_field);
    if (!keys || (*env)->ExceptionCheck(env)) goto failed;
    buttons = (*env)->GetStaticObjectField(env, bridge->glfw, button_field);
    if (!buttons || (*env)->ExceptionCheck(env)) goto failed;
    bridge->keys = (*env)->GetDirectBufferAddress(env, keys);
    bridge->buttons = (*env)->GetDirectBufferAddress(env, buttons);
    if (!bridge->keys || !bridge->buttons) goto failed;
    (*env)->DeleteLocalRef(env, keys);
    (*env)->DeleteLocalRef(env, buttons);
    return true;
failed:
    (*env)->DeleteLocalRef(env, keys);
    (*env)->DeleteLocalRef(env, buttons);
    (*env)->DeleteGlobalRef(env, bridge->glfw);
    memset(bridge, 0, sizeof(*bridge));
    return false;
}
#endif
