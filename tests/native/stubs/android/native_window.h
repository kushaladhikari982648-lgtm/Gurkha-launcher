#ifndef TEST_WINDOW_H
#define TEST_WINDOW_H
#include <stdatomic.h>
typedef struct ANativeWindow { atomic_int refs; int id; } ANativeWindow;
void ANativeWindow_acquire(ANativeWindow *window);
void ANativeWindow_release(ANativeWindow *window);
int ANativeWindow_setBuffersGeometry(ANativeWindow *, int, int, int);
#endif
