#ifndef GURKHA_LOG_BUFFER_H
#define GURKHA_LOG_BUFFER_H
#include <stddef.h>
#include <stdbool.h>
#include <string.h>

/* read(2) buffers need not contain a NUL. Never scan beyond the bytes received. */
static inline bool log_buffer_sensitive(const char *data, size_t length) {
    static const char marker[] = "Session ID is";
    const size_t size = sizeof(marker) - 1;
    if (length < size) return false;
    for (size_t i = 0; i <= length - size; ++i)
        if (!memcmp(data + i, marker, size)) return true;
    return false;
}
#endif
