#ifndef TEST_ENVIRON_H
#define TEST_ENVIRON_H
#include <stdbool.h>
#include <ctxbridges/common.h>
struct test_environ { basic_render_window_t *mainWindowBundle; bool force_vsync; };
extern struct test_environ *pojav_environ;
#endif
