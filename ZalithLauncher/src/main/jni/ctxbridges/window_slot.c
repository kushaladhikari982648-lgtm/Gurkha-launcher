#include "window_slot.h"
#include <android/native_window.h>
#include <pthread.h>

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static ANativeWindow *published;
static uint64_t generation;

void bridge_publish_window(ANativeWindow *window) {
    pthread_mutex_lock(&lock);
    ANativeWindow *old = published;
    published = window;
    ++generation;
    pthread_mutex_unlock(&lock);
    if (old) ANativeWindow_release(old);
}
ANativeWindow *bridge_acquire_window(uint64_t *version) {
    pthread_mutex_lock(&lock);
    ANativeWindow *window = published;
    if (window) ANativeWindow_acquire(window);
    if (version) *version = generation;
    pthread_mutex_unlock(&lock);
    return window;
}
uint64_t bridge_window_generation(void) {
    pthread_mutex_lock(&lock);
    uint64_t result = generation;
    pthread_mutex_unlock(&lock);
    return result;
}
