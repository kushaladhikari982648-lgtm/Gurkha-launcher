/*
 * V3 input bridge implementation.
 *
 * Status:
 * - Active development
 * - Works with some bugs:
 *  + Modded versions gives broken stuff..
 *
 * 
 * - Implements glfwSetCursorPos() to handle grab camera pos correctly.
 */
 
#include <assert.h>
#include <dlfcn.h>
#include <jni.h>
#include <libgen.h>
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>
#include <math.h>

#include "log.h"
#include "utils.h"
#include "runtime_bridge.h"
#include "environ/environ.h"

#define EVENT_TYPE_CHAR 1000
#define EVENT_TYPE_CHAR_MODS 1001
#define EVENT_TYPE_CURSOR_ENTER 1002
#define EVENT_TYPE_FRAMEBUFFER_SIZE 1004
#define EVENT_TYPE_KEY 1005
#define EVENT_TYPE_MOUSE_BUTTON 1006
#define EVENT_TYPE_SCROLL 1007
#define EVENT_TYPE_WINDOW_SIZE 1008

/* Persistent lifecycle state survives events that arrive before the game window/JVM.
 * Android only writes atomics; GLFW reads them on its own event thread. No callbacks
 * or game HashMap writes from the Android UI thread. */
#define WINDOW_STATE_FOCUSED 1
#define WINDOW_STATE_VISIBLE 2
static atomic_int android_window_state = 0;

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetWindowState(
        JNIEnv* env, jclass clazz, jint flag, jboolean enabled) {
    if (flag != WINDOW_STATE_FOCUSED && flag != WINDOW_STATE_VISIBLE) return;
    if (enabled) atomic_fetch_or(&android_window_state, flag);
    else atomic_fetch_and(&android_window_state, ~flag);
}

JNIEXPORT jint JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeGetWindowState(
        JNIEnv* env, jclass clazz) {
    return atomic_load(&android_window_state);
}

static void registerFunctions(JNIEnv *env);

/**
 * Resolve the GLFW class, method IDs, and direct input buffers on demand after libpojavexec
 * and liblwjgl have finished loading.
 *
 * This MUST NOT run inside JNI_OnLoad: in LWJGL 3.4.1 (Minecraft 26.1/26.2),
 * org.lwjgl.system.Library.<clinit> calls System.loadLibrary("pojavexec") at line 63 before
 * Library.loadSystem("org.lwjgl", JNI_LIBRARY_NAME) loads liblwjgl.so at line 67. Calling
 * GetStaticMethodID/GetStaticFieldID/GetStaticObjectField on org.lwjgl.glfw.GLFW inside
 * JNI_OnLoad forces GLFW.<clinit> -> GLFWErrorCallbackI.<clinit> -> LibFFI.<clinit> to run
 * while Library.<clinit> is still paused at line 63 on the same thread, causing
 * LibFFI.FFI_TYPE_DOUBLE() to fail with UnsatisfiedLinkError and poisoning LibFFI.
 */
jboolean ensureGlfwRuntimeBridgeInitialized(JNIEnv *env) {
    const char *backend = getenv("POJAV_WINDOW_BACKEND");
    if (backend && strcmp(backend, "sdl") == 0) return JNI_TRUE;
    if (pojav_environ->vmGlfwClass != NULL &&
        pojav_environ->method_glftSetWindowAttrib != NULL &&
        pojav_environ->method_internalWindowSizeChanged != NULL &&
        pojav_environ->keyDownBuffer != NULL &&
        pojav_environ->mouseDownBuffer != NULL) {
        return JNI_TRUE;
    }
    if (env == NULL && pojav_environ->runtimeJavaVMPtr != NULL) {
        jint status = (*pojav_environ->runtimeJavaVMPtr)->GetEnv(
                pojav_environ->runtimeJavaVMPtr, (void**) &env, JNI_VERSION_1_4);
        if (status == JNI_EDETACHED) {
            (*pojav_environ->runtimeJavaVMPtr)->AttachCurrentThread(
                    pojav_environ->runtimeJavaVMPtr, &env, NULL);
        }
    }
    if (env == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "Native", "ensureGlfwRuntimeBridgeInitialized: no game JVM JNIEnv available");
        return JNI_FALSE;
    }
    struct gurkha_runtime_bridge bridge;
    if (!gurkha_init_runtime_bridge(env, backend, &bridge)) {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        __android_log_print(ANDROID_LOG_ERROR, "Native", "ensureGlfwRuntimeBridgeInitialized: failed to resolve GLFW bridge members");
        return JNI_FALSE;
    }
    pojav_environ->vmGlfwClass = bridge.glfw;
    pojav_environ->method_glftSetWindowAttrib = bridge.set_attrib;
    pojav_environ->method_internalWindowSizeChanged = bridge.resize;
    pojav_environ->keyDownBuffer = bridge.keys;
    pojav_environ->mouseDownBuffer = bridge.buttons;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_GLFW_nativeInitializeGLFWNativeBridge(
        JNIEnv* env, __attribute__((unused)) jclass clazz) {
    ensureGlfwRuntimeBridgeInitialized(env);
}

jint JNI_OnLoad(JavaVM* vm, __attribute__((unused)) void* reserved) {
    if (pojav_environ->dalvikJavaVMPtr == NULL) {
        __android_log_print(ANDROID_LOG_INFO, "Native", "Saving DVM environ...");
        //Save dalvik global JavaVM pointer
        pojav_environ->dalvikJavaVMPtr = vm;
        (*vm)->GetEnv(vm, (void**) &pojav_environ->dalvikJNIEnvPtr_ANDROID, JNI_VERSION_1_4);
        pojav_environ->bridgeClazz = (*pojav_environ->dalvikJNIEnvPtr_ANDROID)->NewGlobalRef(pojav_environ->dalvikJNIEnvPtr_ANDROID,(*pojav_environ->dalvikJNIEnvPtr_ANDROID) ->FindClass(pojav_environ->dalvikJNIEnvPtr_ANDROID,"org/lwjgl/glfw/CallbackBridge"));
        pojav_environ->method_accessAndroidClipboard = (*pojav_environ->dalvikJNIEnvPtr_ANDROID)->GetStaticMethodID(pojav_environ->dalvikJNIEnvPtr_ANDROID, pojav_environ->bridgeClazz, "accessAndroidClipboard", "(ILjava/lang/String;)Ljava/lang/String;");
        pojav_environ->method_onGrabStateChanged = (*pojav_environ->dalvikJNIEnvPtr_ANDROID)->GetStaticMethodID(pojav_environ->dalvikJNIEnvPtr_ANDROID, pojav_environ->bridgeClazz, "onGrabStateChanged", "(Z)V");
        pojav_environ->isUseStackQueueCall = JNI_FALSE;
    } else if (pojav_environ->dalvikJavaVMPtr != vm) {
        __android_log_print(ANDROID_LOG_INFO, "Native", "Saving JVM environ...");
        pojav_environ->runtimeJavaVMPtr = vm;
        if ((*vm)->GetEnv(vm, (void**) &pojav_environ->runtimeJNIEnvPtr_JRE, JNI_VERSION_1_4) != JNI_OK ||
            pojav_environ->runtimeJNIEnvPtr_JRE == NULL) {
            return JNI_ERR;
        }
        const char *backend = getenv("POJAV_WINDOW_BACKEND");
        __android_log_print(ANDROID_LOG_INFO, "Native", "Runtime window backend: %s", backend ? backend : "glfw");
        hookExec();
        installLwjglDlopenHook();
        installEMUIIteratorMititgation();
    }

    if(pojav_environ->dalvikJavaVMPtr == vm) {
        //perform in all DVM instances, not only during first ever set up
        JNIEnv *env;
        (*vm)->GetEnv(vm, (void**) &env, JNI_VERSION_1_4);
        registerFunctions(env);
    }
    pojav_environ->isGrabbing = JNI_FALSE;
    
    return JNI_VERSION_1_4;
}

#define ADD_CALLBACK_WWIN(NAME) \
JNIEXPORT jlong JNICALL Java_org_lwjgl_glfw_GLFW_nglfwSet##NAME##Callback(JNIEnv * env, jclass cls, jlong window, jlong callbackptr) { \
    ensureGlfwRuntimeBridgeInitialized(env); \
    void** oldCallback = (void**) &pojav_environ->GLFW_invoke_##NAME; \
    pojav_environ->GLFW_invoke_##NAME = (GLFW_invoke_##NAME##_func*) (uintptr_t) callbackptr; \
    return (jlong) (uintptr_t) *oldCallback; \
}

ADD_CALLBACK_WWIN(Char)
ADD_CALLBACK_WWIN(CharMods)
ADD_CALLBACK_WWIN(CursorEnter)
ADD_CALLBACK_WWIN(CursorPos)
ADD_CALLBACK_WWIN(FramebufferSize)
ADD_CALLBACK_WWIN(Key)
ADD_CALLBACK_WWIN(MouseButton)
ADD_CALLBACK_WWIN(Scroll)
ADD_CALLBACK_WWIN(WindowSize)

#undef ADD_CALLBACK_WWIN

void handleFramebufferSizeJava(jlong window, jint w, jint h) {
    JNIEnv *env = pojav_environ->runtimeJNIEnvPtr_JRE;
    if (!ensureGlfwRuntimeBridgeInitialized(env) || env == NULL ||
        pojav_environ->vmGlfwClass == NULL ||
        pojav_environ->method_internalWindowSizeChanged == NULL) {
        return;
    }
    // internalWindowSizeChanged is "(JII)V": the window must be passed as a 64-bit jlong. On 32-bit
    // devices a C long is only 32 bits, so passing (long)window shifted every vararg and Java received a
    // garbage window and size, which broke resizing and crashed the game (PojavLauncher b5de32d3b).
    (*env)->CallStaticVoidMethod(env, pojav_environ->vmGlfwClass, pojav_environ->method_internalWindowSizeChanged, window, w, h);
}

void pojavPumpEvents(void* window) {
    if(pojav_environ->shouldUpdateMouse) {
        pojav_environ->GLFW_invoke_CursorPos(window, floor(pojav_environ->cursorX),
                                             floor(pojav_environ->cursorY));
    }

    size_t index = pojav_environ->outEventIndex;
    size_t targetIndex = pojav_environ->outTargetIndex;

    while (targetIndex != index) {
        GLFWInputEvent event = pojav_environ->events[index];
        switch (event.type) {
            case EVENT_TYPE_CHAR:
                if(pojav_environ->GLFW_invoke_Char) pojav_environ->GLFW_invoke_Char(window, event.i1);
                break;
            case EVENT_TYPE_CHAR_MODS:
                if(pojav_environ->GLFW_invoke_CharMods) pojav_environ->GLFW_invoke_CharMods(window, event.i1, event.i2);
                break;
            case EVENT_TYPE_KEY:
                if(pojav_environ->GLFW_invoke_Key) pojav_environ->GLFW_invoke_Key(window, event.i1, event.i2, event.i3, event.i4);
                break;
            case EVENT_TYPE_MOUSE_BUTTON:
                if(pojav_environ->GLFW_invoke_MouseButton) pojav_environ->GLFW_invoke_MouseButton(window, event.i1, event.i2, event.i3);
                break;
            case EVENT_TYPE_SCROLL:
                if(pojav_environ->GLFW_invoke_Scroll) pojav_environ->GLFW_invoke_Scroll(window, event.i1, event.i2);
                break;
            case EVENT_TYPE_FRAMEBUFFER_SIZE:
                handleFramebufferSizeJava(pojav_environ->showingWindow, event.i1, event.i2);
                if(pojav_environ->GLFW_invoke_FramebufferSize) pojav_environ->GLFW_invoke_FramebufferSize(window, event.i1, event.i2);
                break;
            case EVENT_TYPE_WINDOW_SIZE:
                handleFramebufferSizeJava(pojav_environ->showingWindow, event.i1, event.i2);
                if(pojav_environ->GLFW_invoke_WindowSize) pojav_environ->GLFW_invoke_WindowSize(window, event.i1, event.i2);
                break;
        }

        index++;
        if (index >= EVENT_WINDOW_SIZE)
            index -= EVENT_WINDOW_SIZE;
    }

    // The out target index is updated by the rewinder
}

/** Prepare the library for sending out callbacks to all windows */
void pojavStartPumping() {
    size_t counter = atomic_load_explicit(&pojav_environ->eventCounter, memory_order_acquire);
    size_t index = pojav_environ->outEventIndex;

    unsigned targetIndex = index + counter;
    if (targetIndex >= EVENT_WINDOW_SIZE)
        targetIndex -= EVENT_WINDOW_SIZE;

    // Only accessed by one unique thread, no need for atomic store
    pojav_environ->inEventCount = counter;
    pojav_environ->outTargetIndex = targetIndex;

    //PumpEvents is called for every window, so this logic should be there in order to correctly distribute events to all windows.
    if((pojav_environ->cLastX != pojav_environ->cursorX || pojav_environ->cLastY != pojav_environ->cursorY) && pojav_environ->GLFW_invoke_CursorPos) {
        pojav_environ->cLastX = pojav_environ->cursorX;
        pojav_environ->cLastY = pojav_environ->cursorY;
        pojav_environ->shouldUpdateMouse = true;
    }
}

/** Prepare the library for the next round of new events */
void pojavStopPumping() {
    pojav_environ->outEventIndex = pojav_environ->outTargetIndex;

    // New events may have arrived while pumping, so remove only the difference before the start and end of execution
    atomic_fetch_sub_explicit(&pojav_environ->eventCounter, pojav_environ->inEventCount, memory_order_acquire);
    // Make sure the next frame won't send mouse updates if it's unnecessary
    pojav_environ->shouldUpdateMouse = false;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPos(JNIEnv *env, __attribute__((unused)) jclass clazz, __attribute__((unused)) jlong window, jobject xpos,
                                          jobject ypos) {
    *(double*)(*env)->GetDirectBufferAddress(env, xpos) = pojav_environ->cursorX;
    *(double*)(*env)->GetDirectBufferAddress(env, ypos) = pojav_environ->cursorY;
}

JNIEXPORT void JNICALL JavaCritical_org_lwjgl_glfw_GLFW_nglfwGetCursorPosA(__attribute__((unused)) jlong window, jint lengthx, jdouble* xpos, jint lengthy, jdouble* ypos) {
    *xpos = pojav_environ->cursorX;
    *ypos = pojav_environ->cursorY;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPosA(JNIEnv *env, __attribute__((unused)) jclass clazz, __attribute__((unused)) jlong window,
                                            jdoubleArray xpos, jdoubleArray ypos) {
    (*env)->SetDoubleArrayRegion(env, xpos, 0,1, &pojav_environ->cursorX);
    (*env)->SetDoubleArrayRegion(env, ypos, 0,1, &pojav_environ->cursorY);
}

JNIEXPORT void JNICALL JavaCritical_org_lwjgl_glfw_GLFW_glfwSetCursorPos(__attribute__((unused)) jlong window, jdouble xpos,
                                                                         jdouble ypos) {
    pojav_environ->cLastX = pojav_environ->cursorX = xpos;
    pojav_environ->cLastY = pojav_environ->cursorY = ypos;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_glfwSetCursorPos(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jclass clazz, __attribute__((unused)) jlong window, jdouble xpos,
                                          jdouble ypos) {
    JavaCritical_org_lwjgl_glfw_GLFW_glfwSetCursorPos(window, xpos, ypos);
}



void sendData(int type, int i1, int i2, int i3, int i4) {
    GLFWInputEvent *event = &pojav_environ->events[pojav_environ->inEventIndex];
    event->type = type;
    event->i1 = i1;
    event->i2 = i2;
    event->i3 = i3;
    event->i4 = i4;

    if (++pojav_environ->inEventIndex >= EVENT_WINDOW_SIZE)
        pojav_environ->inEventIndex -= EVENT_WINDOW_SIZE;

    atomic_fetch_add_explicit(&pojav_environ->eventCounter, 1, memory_order_acquire);
}

/**
 * This function is meant as a substitute for SharedLibraryUtil.getLibraryPath() that just returns 0
 * (thus making the parent Java function return null). This is done to avoid using the LWJGL's default function,
 * which will hang the crappy EMUI linker by dlopen()ing inside of dl_iterate_phdr().
 * @return 0, to make the parent Java function return null immediately.
 * For reference: https://github.com/PojavLauncherTeam/lwjgl3/blob/fix_huawei_hang/modules/lwjgl/core/src/main/java/org/lwjgl/system/SharedLibraryUtil.java
 */
jint getLibraryPath_fix(__attribute__((unused)) JNIEnv *env,
                        __attribute__((unused)) jclass class,
                        __attribute__((unused)) jlong pLibAddress,
                        __attribute__((unused)) jlong sOutAddress,
                        __attribute__((unused)) jint bufSize){
    return 0;
}

/**
 * Install the linker hang mitigation that is meant to prevent linker hangs on old EMUI firmware.
 */
void installEMUIIteratorMititgation() {
    if(getenv("POJAV_EMUI_ITERATOR_MITIGATE") == NULL) return;
    __android_log_print(ANDROID_LOG_INFO, "EMUIIteratorFix", "Installing...");
    JNIEnv* env = pojav_environ->runtimeJNIEnvPtr_JRE;
    jclass sharedLibraryUtil = (*env)->FindClass(env, "org/lwjgl/system/SharedLibraryUtil");
    if(sharedLibraryUtil == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "EMUIIteratorFix", "Failed to find the target class");
        (*env)->ExceptionClear(env);
        return;
    }
    JNINativeMethod getLibraryPathMethod[] = {
            {"getLibraryPath", "(JJI)I", &getLibraryPath_fix}
    };
    if((*env)->RegisterNatives(env, sharedLibraryUtil, getLibraryPathMethod, 1) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "EMUIIteratorFix", "Failed to register the mitigation method");
        (*env)->ExceptionClear(env);
    }
}

void critical_set_stackqueue(jboolean use_input_stack_queue) {
    pojav_environ->isUseStackQueueCall = (int) use_input_stack_queue;
}

void noncritical_set_stackqueue(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jclass clazz, jboolean use_input_stack_queue) {
    critical_set_stackqueue(use_input_stack_queue);
}

JNIEXPORT jstring JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeClipboard(JNIEnv* env, __attribute__((unused)) jclass clazz, jint action, jbyteArray copySrc) {
#ifdef DEBUG
    LOGD("Debug: Clipboard access is going on\n", pojav_environ->isUseStackQueueCall);
#endif

    JavaVM *dalvikVM = pojav_environ->dalvikJavaVMPtr;
    JNIEnv *dalvikEnv = NULL;
    bool attachedHere = false;
    if ((*dalvikVM)->GetEnv(dalvikVM, (void **) &dalvikEnv, JNI_VERSION_1_4) == JNI_EDETACHED) {
        if ((*dalvikVM)->AttachCurrentThread(dalvikVM, &dalvikEnv, NULL) != JNI_OK) return NULL;
        attachedHere = true;
    }
    assert(dalvikEnv != NULL);
    assert(pojav_environ->bridgeClazz != NULL);
    
    LOGD("Clipboard: Converting string\n");
    char *copySrcC;
    jstring copyDst = NULL;
    if (copySrc) {
        copySrcC = (char *)((*env)->GetByteArrayElements(env, copySrc, NULL));
        copyDst = (*dalvikEnv)->NewStringUTF(dalvikEnv, copySrcC);
    }

    LOGD("Clipboard: Calling 2nd\n");
    jstring pasteDst = convertStringJVM(dalvikEnv, env, (jstring) (*dalvikEnv)->CallStaticObjectMethod(dalvikEnv, pojav_environ->bridgeClazz, pojav_environ->method_accessAndroidClipboard, action, copyDst));

    if (copySrc) {
        (*dalvikEnv)->DeleteLocalRef(dalvikEnv, copyDst);    
        (*env)->ReleaseByteArrayElements(env, copySrc, (jbyte *)copySrcC, 0);
    }
    const char *backend = getenv("POJAV_WINDOW_BACKEND");
    bool isSdl = backend != NULL && strcmp(backend, "sdl") == 0;
    if (attachedHere && !isSdl) (*dalvikVM)->DetachCurrentThread(dalvikVM);
    return pasteDst;
}

/**
 * Called from the JRE side (org.lwjgl.glfw.CallbackBridge of the lwjgl3-sdl component, for example right
 * before SDL_Init) to notify the launcher: calls the launcher's CallbackBridge.notifyLauncher(int, int[]).
 */
JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeNotifyLauncher(JNIEnv* env, __attribute__((unused)) jclass clazz, jint type, jintArray action) {
    if (pojav_environ == NULL) return JNI_FALSE;
    JavaVM* dalvikVM = pojav_environ->dalvikJavaVMPtr;
    if (dalvikVM == NULL || pojav_environ->bridgeClazz == NULL) return JNI_FALSE;

    JNIEnv* dalvikEnv = NULL;
    bool attachedHere = false;
    jint status = (*dalvikVM)->GetEnv(dalvikVM, (void**) &dalvikEnv, JNI_VERSION_1_4);
    if (status == JNI_EDETACHED) {
        if ((*dalvikVM)->AttachCurrentThread(dalvikVM, &dalvikEnv, NULL) != JNI_OK) return JNI_FALSE;
        attachedHere = true;
    } else if (status != JNI_OK) return JNI_FALSE;

    jboolean result = JNI_FALSE;
    jintArray dalvikAction = NULL;
    jsize count = action != NULL ? (*env)->GetArrayLength(env, action) : 0;
    // Small control messages only. Avoid unchecked allocation/pinned arrays across two VMs.
    if (count > 64 || (*env)->ExceptionCheck(env)) goto cleanup_notification;
    jint values[64];
    if (count > 0) {
        (*env)->GetIntArrayRegion(env, action, 0, count, values);
        if ((*env)->ExceptionCheck(env)) goto cleanup_notification;
    }
    dalvikAction = (*dalvikEnv)->NewIntArray(dalvikEnv, count);
    if (dalvikAction == NULL) goto cleanup_notification;
    if (count > 0) (*dalvikEnv)->SetIntArrayRegion(dalvikEnv, dalvikAction, 0, count, values);
    if ((*dalvikEnv)->ExceptionCheck(dalvikEnv)) goto cleanup_notification;

    jmethodID notifyLauncher = (*dalvikEnv)->GetStaticMethodID(dalvikEnv, pojav_environ->bridgeClazz, "notifyLauncher", "(I[I)Z");
    if (notifyLauncher != NULL) {
        result = (*dalvikEnv)->CallStaticBooleanMethod(dalvikEnv, pojav_environ->bridgeClazz, notifyLauncher, type, dalvikAction);
    }
cleanup_notification:
    if ((*dalvikEnv)->ExceptionCheck(dalvikEnv)) {
        (*dalvikEnv)->ExceptionDescribe(dalvikEnv);
        (*dalvikEnv)->ExceptionClear(dalvikEnv);
        result = JNI_FALSE;
    }
    (*dalvikEnv)->DeleteLocalRef(dalvikEnv, dalvikAction);
    /* SDL.setupJNI() caches dalvikEnv in SDL's pthread_key_t mThreadKey on this thread and
     * registers Android_JNI_ThreadDestroyed to detach when the pthread exits. Detaching here
     * frees dalvikEnv while mThreadKey still points to it, causing SIGSEGV in Android_JNI_InitTouch
     * (libSDL3.so+0x7320a on armeabi-v7a). Only detach if SDL init failed. */
    const char *backend = getenv("POJAV_WINDOW_BACKEND");
    bool keepAttachedForSdl = (type == 0 && result == JNI_TRUE) || (backend != NULL && strcmp(backend, "sdl") == 0);
    if (attachedHere && !keepAttachedForSdl) (*dalvikVM)->DetachCurrentThread(dalvikVM);
    return result;
}

JNIEXPORT jboolean JNICALL JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(jboolean inputReady) {
#ifdef DEBUG
    LOGD("Debug: Changing input state, isReady=%d, pojav_environ->isUseStackQueueCall=%d\n", inputReady, pojav_environ->isUseStackQueueCall);
#endif
    __android_log_print(ANDROID_LOG_INFO, "NativeInput", "Input ready: %i", inputReady);
    pojav_environ->isInputReady = inputReady;
    return pojav_environ->isUseStackQueueCall;
}

JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jboolean inputReady) {
    return JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(inputReady);
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetGrabbing(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jboolean grabbing) {
    JavaVM *dalvikVM = pojav_environ->dalvikJavaVMPtr;
    JNIEnv *dalvikEnv = NULL;
    bool attachedHere = false;
    if ((*dalvikVM)->GetEnv(dalvikVM, (void **) &dalvikEnv, JNI_VERSION_1_4) == JNI_EDETACHED) {
        if ((*dalvikVM)->AttachCurrentThread(dalvikVM, &dalvikEnv, NULL) != JNI_OK) return;
        attachedHere = true;
    }
    (*dalvikEnv)->CallStaticVoidMethod(dalvikEnv, pojav_environ->bridgeClazz, pojav_environ->method_onGrabStateChanged, grabbing);
    const char *backend = getenv("POJAV_WINDOW_BACKEND");
    bool isSdl = backend != NULL && strcmp(backend, "sdl") == 0;
    if (attachedHere && !isSdl) (*dalvikVM)->DetachCurrentThread(dalvikVM);
    pojav_environ->isGrabbing = grabbing;
}

jboolean critical_send_char(jchar codepoint) {
    if (pojav_environ->GLFW_invoke_Char && pojav_environ->isInputReady) {
        if (pojav_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_CHAR, codepoint, 0, 0, 0);
        } else {
            pojav_environ->GLFW_invoke_Char((void*) pojav_environ->showingWindow, (unsigned int) codepoint);
        }
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

jboolean noncritical_send_char(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jchar codepoint) {
    return critical_send_char(codepoint);
}

jboolean critical_send_char_mods(jchar codepoint, jint mods) {
    if (pojav_environ->GLFW_invoke_CharMods && pojav_environ->isInputReady) {
        if (pojav_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_CHAR_MODS, (int) codepoint, mods, 0, 0);
        } else {
            pojav_environ->GLFW_invoke_CharMods((void*) pojav_environ->showingWindow, codepoint, mods);
        }
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

jboolean noncritical_send_char_mods(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jchar codepoint, jint mods) {
    return critical_send_char_mods(codepoint, mods);
}
/*
JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSendCursorEnter(JNIEnv* env, jclass clazz, jint entered) {
    if (pojav_environ->GLFW_invoke_CursorEnter && pojav_environ->isInputReady) {
        pojav_environ->GLFW_invoke_CursorEnter((void*) pojav_environ->showingWindow, entered);
    }
}
*/

void critical_send_cursor_pos(jfloat x, jfloat y) {
#ifdef DEBUG
    LOGD("Sending cursor position \n");
#endif
    if (pojav_environ->GLFW_invoke_CursorPos && pojav_environ->isInputReady) {
#ifdef DEBUG
        LOGD("pojav_environ->GLFW_invoke_CursorPos && pojav_environ->isInputReady \n");
#endif
        if (!pojav_environ->isCursorEntered) {
            if (pojav_environ->GLFW_invoke_CursorEnter) {
                pojav_environ->isCursorEntered = true;
                if (pojav_environ->isUseStackQueueCall) {
                    sendData(EVENT_TYPE_CURSOR_ENTER, 1, 0, 0, 0);
                } else {
                    pojav_environ->GLFW_invoke_CursorEnter((void*) pojav_environ->showingWindow, 1);
                }
            } else if (pojav_environ->isGrabbing) {
                // Some Minecraft versions does not use GLFWCursorEnterCallback
                // This is a smart check, as Minecraft will not in grab mode if already not.
                pojav_environ->isCursorEntered = true;
            }
        }

        if (!pojav_environ->isUseStackQueueCall) {
            pojav_environ->GLFW_invoke_CursorPos((void*) pojav_environ->showingWindow, (double) (x), (double) (y));
        } else {
            pojav_environ->cursorX = x;
            pojav_environ->cursorY = y;
        }
    }
}

void noncritical_send_cursor_pos(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz,  jfloat x, jfloat y) {
    critical_send_cursor_pos(x, y);
}
#define max(a,b) \
   ({ __typeof__ (a) _a = (a); \
       __typeof__ (b) _b = (b); \
     _a > _b ? _a : _b; })
void critical_send_key(jint key, jint scancode, jint action, jint mods) {
    if (pojav_environ->GLFW_invoke_Key && pojav_environ->isInputReady) {
        if (pojav_environ->keyDownBuffer != NULL && key >= 31 && key < 31 + 317) {
            pojav_environ->keyDownBuffer[key - 31] = (jbyte) action;
        }
        if (pojav_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_KEY, key, scancode, action, mods);
        } else {
            pojav_environ->GLFW_invoke_Key((void*) pojav_environ->showingWindow, key, scancode, action, mods);
        }
    }
}
void noncritical_send_key(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint key, jint scancode, jint action, jint mods) {
    critical_send_key(key, scancode, action, mods);
}

void critical_send_mouse_button(jint button, jint action, jint mods) {
    if (pojav_environ->GLFW_invoke_MouseButton && pojav_environ->isInputReady) {
        if (pojav_environ->mouseDownBuffer != NULL && button >= 0 && button < 8) {
            pojav_environ->mouseDownBuffer[button] = (jbyte) action;
        }
        if (pojav_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_MOUSE_BUTTON, button, action, mods, 0);
        } else {
            pojav_environ->GLFW_invoke_MouseButton((void*) pojav_environ->showingWindow, button, action, mods);
        }
    }
}

void noncritical_send_mouse_button(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint button, jint action, jint mods) {
    critical_send_mouse_button(button, action, mods);
}

void critical_send_screen_size(jint width, jint height) {
    pojav_environ->savedWidth = width;
    pojav_environ->savedHeight = height;
    if (pojav_environ->isInputReady) {
        if (pojav_environ->GLFW_invoke_FramebufferSize) {
            if (pojav_environ->isUseStackQueueCall) {
                sendData(EVENT_TYPE_FRAMEBUFFER_SIZE, width, height, 0, 0);
            } else {
                pojav_environ->GLFW_invoke_FramebufferSize((void*) pojav_environ->showingWindow, width, height);
            }
        }

        if (pojav_environ->GLFW_invoke_WindowSize) {
            if (pojav_environ->isUseStackQueueCall) {
                sendData(EVENT_TYPE_WINDOW_SIZE, width, height, 0, 0);
            } else {
                pojav_environ->GLFW_invoke_WindowSize((void*) pojav_environ->showingWindow, width, height);
            }
        }
    }
}

void noncritical_send_screen_size(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint width, jint height) {
    critical_send_screen_size(width, height);
}

void critical_send_scroll(jdouble xoffset, jdouble yoffset) {
    if (pojav_environ->GLFW_invoke_Scroll && pojav_environ->isInputReady) {
        if (pojav_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_SCROLL, (int)xoffset, (int)yoffset, 0, 0);
        } else {
            pojav_environ->GLFW_invoke_Scroll((void*) pojav_environ->showingWindow, (double) xoffset, (double) yoffset);
        }
    }
}

void noncritical_send_scroll(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jdouble xoffset, jdouble yoffset) {
    critical_send_scroll(xoffset, yoffset);
}


JNIEXPORT void JNICALL Java_org_lwjgl_glfw_GLFW_nglfwSetShowingWindow(JNIEnv* env, __attribute__((unused)) jclass clazz, jlong window) {
    ensureGlfwRuntimeBridgeInitialized(env);
    pojav_environ->showingWindow = (jlong) window;
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetWindowAttrib(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint attrib, jint value) {
    // Check for stack queue no longer necessary here as the JVM crash's origin is resolved
    if (!pojav_environ->showingWindow || !pojav_environ->vmGlfwClass || !pojav_environ->runtimeJavaVMPtr) {
        // If the window is not shown, there is nothing to do yet.
        return;
    }

    // We cannot use pojav_environ->runtimeJNIEnvPtr_JRE here because that environment is attached
    // on the thread that loaded pojavexec (which is the thread that first references the GLFW class)
    // But this method is only called from the Android UI thread
    // Technically the better solution would be to have a permanently attached env pointer stored
    // in environ for the Android UI thread but this is the only place that uses it
    // (very rarely, only in lifecycle callbacks) so i dont care
    JavaVM* jvm = pojav_environ->runtimeJavaVMPtr;
    JNIEnv *jvm_env = NULL;
    jint env_result = (*jvm)->GetEnv(jvm, (void**)&jvm_env, JNI_VERSION_1_4);
    if(env_result == JNI_EDETACHED) {
        env_result = (*jvm)->AttachCurrentThread(jvm, &jvm_env, NULL);
    }
    if(env_result != JNI_OK) {
        printf("input_bridge nativeSetWindowAttrib() JNI call failed: %i\n", env_result);
        return;
    }
    (*jvm_env)->CallStaticVoidMethod(
            jvm_env, pojav_environ->vmGlfwClass,
            pojav_environ->method_glftSetWindowAttrib,
            (jlong) pojav_environ->showingWindow, attrib, value
    );

    // Attaching every time is annoying, so stick the attachment to the Android GUI thread around
}
const static JNINativeMethod critical_fcns[] = {
        {"nativeSetUseInputStackQueue", "(Z)V", critical_set_stackqueue},
        {"nativeSendChar", "(C)Z", critical_send_char},
        {"nativeSendCharMods", "(CI)Z", critical_send_char_mods},
        {"nativeSendKey", "(IIII)V", critical_send_key},
        {"nativeSendCursorPos", "(FF)V", critical_send_cursor_pos},
        {"nativeSendMouseButton", "(III)V", critical_send_mouse_button},
        {"nativeSendScroll", "(DD)V", critical_send_scroll},
        {"nativeSendScreenSize", "(II)V", critical_send_screen_size}
};

const static JNINativeMethod noncritical_fcns[] = {
        {"nativeSetUseInputStackQueue", "(Z)V", noncritical_set_stackqueue},
        {"nativeSendChar", "(C)Z", noncritical_send_char},
        {"nativeSendCharMods", "(CI)Z", noncritical_send_char_mods},
        {"nativeSendKey", "(IIII)V", noncritical_send_key},
        {"nativeSendCursorPos", "(FF)V", noncritical_send_cursor_pos},
        {"nativeSendMouseButton", "(III)V", noncritical_send_mouse_button},
        {"nativeSendScroll", "(DD)V", noncritical_send_scroll},
        {"nativeSendScreenSize", "(II)V", noncritical_send_screen_size}
};


static bool criticalNativeAvailable;

void dvm_testCriticalNative(void* arg0, void* arg1, void* arg2, void* arg3) {
    if(arg0 != 0 && arg2 == 0 && arg3 == 0) {
        criticalNativeAvailable = false;
    }else if (arg0 == 0 && arg1 == 0){
        criticalNativeAvailable = true;
    }else {
        criticalNativeAvailable = false; // just to be safe
    }
}

static bool tryCriticalNative(JNIEnv *env) {
    static const JNINativeMethod testJNIMethod[] = {
            { "testCriticalNative", "(II)V", dvm_testCriticalNative}
    };
    jclass criticalNativeTest = (*env)->FindClass(env, "net/kdt/pojavlaunch/CriticalNativeTest");
    if(criticalNativeTest == NULL) {
        LOGD("No CriticalNativeTest class found !");
        (*env)->ExceptionClear(env);
        return false;
    }
    jmethodID criticalNativeTestMethod = (*env)->GetStaticMethodID(env, criticalNativeTest, "invokeTest", "()V");
    (*env)->RegisterNatives(env, criticalNativeTest, testJNIMethod, 1);
    (*env)->CallStaticVoidMethod(env, criticalNativeTest, criticalNativeTestMethod);
    (*env)->UnregisterNatives(env, criticalNativeTest);
    return criticalNativeAvailable;
}

static void registerFunctions(JNIEnv *env) {
    bool use_critical_cc = tryCriticalNative(env);
    jclass bridge_class = (*env)->FindClass(env, "org/lwjgl/glfw/CallbackBridge");
    if(use_critical_cc) {
        __android_log_print(ANDROID_LOG_INFO, "pojavexec", "CriticalNative is available. Enjoy the 4.6x times faster input!");
    }else{
        __android_log_print(ANDROID_LOG_INFO, "pojavexec", "CriticalNative is not available. Upgrade, maybe?");
    }
    (*env)->RegisterNatives(env,
                            bridge_class,
                            use_critical_cc ? critical_fcns : noncritical_fcns,
                            sizeof(critical_fcns)/sizeof(critical_fcns[0]));
}
