#ifndef GURKHA_WINDOW_SLOT_H
#define GURKHA_WINDOW_SLOT_H
#include <stdint.h>
struct ANativeWindow;
/* publish takes ownership; acquire returns a reference the caller must release. */
void bridge_publish_window(struct ANativeWindow *window);
struct ANativeWindow *bridge_acquire_window(uint64_t *generation);
uint64_t bridge_window_generation(void);
#endif
