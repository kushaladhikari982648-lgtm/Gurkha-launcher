#include <jni.h>
#include <sys/types.h>
#include <signal.h>
#include <stdbool.h>
#include <unistd.h>
#include <pthread.h>
#include <stdio.h>
#include <fcntl.h>
#include <string.h>
#include <errno.h>
#include <stdlib.h>
#include <environ/environ.h>

#include "stdio_is.h"
#include "log_buffer.h"

//
// Created by maks on 17.02.21.
//

static volatile jobject exitTrap_ctx;
static volatile jclass exitTrap_exitClass;
static volatile jmethodID exitTrap_staticMethod;
static JavaVM *exitTrap_jvm;

static int pfd[2];
static pthread_t logger;
static jmethodID logger_onEventLogged;
static jobject logListener = NULL;
static pthread_mutex_t listener_lock = PTHREAD_MUTEX_INITIALIZER;
static bool logger_started;
static int latestlog_fd = -1;
static pthread_mutex_t file_lock = PTHREAD_MUTEX_INITIALIZER;

static bool recordBytes(const char* buf, size_t len, bool newline) {
    if (log_buffer_sensitive(buf, len)) return false;
    pthread_mutex_lock(&file_lock);
    if (latestlog_fd != -1) {
        size_t offset = 0;
        while (offset < len) {
            ssize_t count = write(latestlog_fd, buf + offset, len - offset);
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) break;
            offset += (size_t)count;
        }
        if (newline) {
            ssize_t count;
            do { count = write(latestlog_fd, "\n", 1); } while (count < 0 && errno == EINTR);
        }
        fdatasync(latestlog_fd);
    }
    pthread_mutex_unlock(&file_lock);
    return true;
}

static bool recordBuffer(const char *buf, size_t len) { return recordBytes(buf, len, false); }

static void notifyListener(JNIEnv *env, jstring text) {
    pthread_mutex_lock(&listener_lock);
    jobject listener = logListener ? (*env)->NewLocalRef(env, logListener) : NULL;
    pthread_mutex_unlock(&listener_lock);
    if (listener) {
        (*env)->CallVoidMethod(env, listener, logger_onEventLogged, text);
        (*env)->DeleteLocalRef(env, listener);
    }
}

static void *logger_thread() {
    JNIEnv *env;
    jstring writeString;

    JavaVM* dvm = pojav_environ->dalvikJavaVMPtr;
    bool attached = (*dvm)->AttachCurrentThread(dvm, &env, NULL) == JNI_OK;

    ssize_t  rsize;
    char buf[2050];

    while (true)
    {
        rsize = read(pfd[0], buf, sizeof(buf)-1);
        if (rsize < 0 && errno == EINTR) continue;
        if (rsize <= 0) break;
        buf[rsize] = '\0';
        bool shouldRecordString = recordBuffer(buf, rsize); //record with newline int latestlog
        if (buf[rsize-1]=='\n')
        {
            rsize=rsize-1; //truncate
        }
        buf[rsize]=0x00;
        if (shouldRecordString && attached) {
            writeString = (*env)->NewStringUTF(env, buf);
            if (writeString) { notifyListener(env, writeString); (*env)->DeleteLocalRef(env, writeString); }
            // A failed UI listener must not kill native file capture on a low-memory phone.
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        }
    }
    if (attached) (*dvm)->DetachCurrentThread(dvm);
    return NULL;
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_Logger_begin(JNIEnv *env, __attribute((unused)) jclass clazz, jstring logPath) {
    jclass ioeClass = (*env)->FindClass(env, "java/io/IOException");
    if (!ioeClass || !logPath) return;
    if (logger_onEventLogged == NULL) {
        jclass listenerClass = (*env)->FindClass(env, "net/kdt/pojavlaunch/Logger$eventLogListener");
        if (!listenerClass) return;
        logger_onEventLogged = (*env)->GetMethodID(env, listenerClass, "onEventLogged", "(Ljava/lang/String;)V");
        (*env)->DeleteLocalRef(env, listenerClass);
        if (!logger_onEventLogged) return;
    }
    const char *path = (*env)->GetStringUTFChars(env, logPath, NULL);
    if (!path) return;
    int file = open(path, O_WRONLY | O_TRUNC | O_CREAT | O_CLOEXEC, 0600);
    int error = errno;
    (*env)->ReleaseStringUTFChars(env, logPath, path);
    if (file < 0) { (*env)->ThrowNew(env, ioeClass, strerror(error)); return; }
    if (!logger_started) {
        if (pipe(pfd) != 0) {
            error = errno; close(file); (*env)->ThrowNew(env, ioeClass, strerror(error)); return;
        }
        fcntl(pfd[0], F_SETFD, FD_CLOEXEC);
        fcntl(pfd[1], F_SETFD, FD_CLOEXEC);
        int saved_out = dup(STDOUT_FILENO), saved_err = dup(STDERR_FILENO);
        if (saved_out < 0 || saved_err < 0 || dup2(pfd[1], STDOUT_FILENO) < 0 || dup2(pfd[1], STDERR_FILENO) < 0) {
            error = errno;
        } else {
            setvbuf(stdout, NULL, _IOLBF, 0);
            setvbuf(stderr, NULL, _IONBF, 0);
            pthread_mutex_lock(&file_lock);
            latestlog_fd = file;
            pthread_mutex_unlock(&file_lock);
            error = pthread_create(&logger, NULL, logger_thread, NULL);
            if (!error) { logger_started = true; pthread_detach(logger); }
        }
        if (!logger_started) {
            if (saved_out >= 0) dup2(saved_out, STDOUT_FILENO);
            if (saved_err >= 0) dup2(saved_err, STDERR_FILENO);
            close(pfd[0]); close(pfd[1]); close(file);
            pthread_mutex_lock(&file_lock); latestlog_fd = -1; pthread_mutex_unlock(&file_lock);
        }
        if (saved_out >= 0) close(saved_out);
        if (saved_err >= 0) close(saved_err);
        if (!logger_started) (*env)->ThrowNew(env, ioeClass, strerror(error));
    } else {
        // Reopening the log must not create a second pipe reader or leak the first one.
        pthread_mutex_lock(&file_lock);
        int old = latestlog_fd; latestlog_fd = file;
        if (old >= 0) close(old);
        pthread_mutex_unlock(&file_lock);
    }
}

static void drainPipeToLog(void) {
    fflush(stdout);
    fflush(stderr);
    if (!logger_started || pfd[0] < 0) return;
    int flags = fcntl(pfd[0], F_GETFL, 0);
    if (flags >= 0) fcntl(pfd[0], F_SETFL, flags | O_NONBLOCK);
    char drain_buf[2048];
    ssize_t n;
    while ((n = read(pfd[0], drain_buf, sizeof(drain_buf))) > 0) {
        recordBuffer(drain_buf, (size_t)n);
    }
}

_Noreturn void nominal_exit(int code, bool is_signal) {
    drainPipeToLog();
    char diagnostic[128];
    int length = snprintf(diagnostic, sizeof(diagnostic), "Native exit hook: code=%d, is_signal=%s\n", code, is_signal ? "true" : "false");
    if (length > 0) recordBuffer(diagnostic, (size_t)length);
    JNIEnv *env;
    jint errorCode = (*exitTrap_jvm)->GetEnv(exitTrap_jvm, (void**)&env, JNI_VERSION_1_6);

    if (errorCode == JNI_EDETACHED)
        errorCode = (*exitTrap_jvm)->AttachCurrentThread(exitTrap_jvm, &env, NULL);

    if (errorCode != JNI_OK)
        killpg(getpgrp(), SIGTERM);

    if (code != 0)
        (*env)->CallStaticVoidMethod(env, exitTrap_exitClass, exitTrap_staticMethod, exitTrap_ctx, code, is_signal);

    // Delete the reference, not gonna need 'em later anyway
    (*env)->DeleteGlobalRef(env, exitTrap_ctx);
    (*env)->DeleteGlobalRef(env, exitTrap_exitClass);

    // A hat trick, if you will
    // Call the Android System.exit() to perform Android's shutdown hooks and do a
    // fully clean exit.
    // After doing this, either of these will happen:
    // 1. Runtime calls exit() for real and it will be handled by ByteHook's recurse handler
    // and redirected back to the OS
    // 2. Zygote sends SIGTERM (no handling necessary, the process perishes)
    // 3. A different thread calls exit() and the hook will go through the exit_tripped path
    jclass systemClass = (*env)->FindClass(env,"java/lang/System");
    jmethodID exitMethod = (*env)->GetStaticMethodID(env, systemClass, "exit", "(I)V");
    (*env)->CallStaticVoidMethod(env, systemClass, exitMethod, 0);
    // System.exit() should not ever return, but the compiler doesn't know about that
    // so put a while loop here
    while(1) {}
}

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_Logger_appendToLog(JNIEnv *env, __attribute((unused)) jclass clazz, jstring text) {
    if (!text) return;
    jsize length = (*env)->GetStringUTFLength(env, text);
    const char *chars = (*env)->GetStringUTFChars(env, text, NULL);
    if (!chars) return;
    bool recorded = recordBytes(chars, (size_t)length, true);
    (*env)->ReleaseStringUTFChars(env, text, chars);
    if (recorded) notifyListener(env, text);
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_Logger_setLogListener(JNIEnv *env, __attribute((unused)) jclass clazz, jobject log_listener) {
    jobject replacement = log_listener ? (*env)->NewGlobalRef(env, log_listener) : NULL;
    if ((*env)->ExceptionCheck(env)) return;
    pthread_mutex_lock(&listener_lock);
    jobject old = logListener;
    logListener = replacement;
    pthread_mutex_unlock(&listener_lock);
    if (old) (*env)->DeleteGlobalRef(env, old);
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_setupExitMethod(JNIEnv *env, jclass clazz,
                                                        jobject context) {
    exitTrap_ctx = (*env)->NewGlobalRef(env,context);
    (*env)->GetJavaVM(env,&exitTrap_jvm);
    exitTrap_exitClass = (*env)->NewGlobalRef(env,(*env)->FindClass(env,"com/movtery/zalithlauncher/ui/activity/ErrorActivity"));
    exitTrap_staticMethod = (*env)->GetStaticMethodID(env,exitTrap_exitClass,"showExitMessage","(Landroid/content/Context;IZ)V");
}