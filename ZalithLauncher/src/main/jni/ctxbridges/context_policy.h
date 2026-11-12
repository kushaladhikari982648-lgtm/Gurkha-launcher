#ifndef GURKHA_CONTEXT_POLICY_H
#define GURKHA_CONTEXT_POLICY_H
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

/* A malformed environment must never become strtol(NULL) or an ES2 downgrade. */
static bool bridge_es_version(const char *text, int *version) {
    if (!text || !*text) { *version = 2; return true; }
    char *end;
    errno = 0;
    long value = strtol(text, &end, 10);
    if (errno || *end || value < 1 || value > 3) return false;
    *version = (int)value;
    return true;
}
static bool bridge_is_sdl(void) {
    const char *backend = getenv("POJAV_WINDOW_BACKEND");
    return backend && strcmp(backend, "sdl") == 0;
}
#endif
