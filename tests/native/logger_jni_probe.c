#include <stdbool.h>
#include <jni.h>
#include <environ/environ.h>
static struct pojav_environ_s state;
struct pojav_environ_s *pojav_environ = &state;
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *unused) {
    (void)unused;
    state.dalvikJavaVMPtr = vm;
    return JNI_VERSION_1_6;
}
