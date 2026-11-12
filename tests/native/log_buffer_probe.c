#include <assert.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
#include "log_buffer.h"
int main(void) {
    size_t page = (size_t)sysconf(_SC_PAGESIZE);
    char *memory = mmap(NULL, page * 2, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(memory != MAP_FAILED);
    assert(!mprotect(memory + page, page, PROT_NONE));
    // No terminating NUL: old strstr implementation reads into the protected page.
    memset(memory, 'X', page);
    assert(!log_buffer_sensitive(memory, page));
    for (size_t n = 0; n <= 64; n++) assert(!log_buffer_sensitive(memory + page - n, n));
    const char *marker = "Session ID is";
    memcpy(memory + page - strlen(marker), marker, strlen(marker));
    assert(log_buffer_sensitive(memory, page));
    assert(!log_buffer_sensitive(memory, page - 1));
    assert(!munmap(memory, page * 2));
    return 0;
}
