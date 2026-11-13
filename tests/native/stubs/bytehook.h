#ifndef STUB_BYTEHOOK_H
#define STUB_BYTEHOOK_H

typedef void* bytehook_stub_t;
typedef void (*bytehook_hooked_t)(
    bytehook_stub_t task_stub,
    int status_code,
    const char* caller_path_name,
    const char* sym_name,
    void* new_func,
    void* prev_func,
    void* arg
);

#define BYTEHOOK_MODE_AUTOMATIC 0
#define BYTEHOOK_STATUS_CODE_OK 0

#define BYTEHOOK_STACK_SCOPE() do {} while (0)
#define BYTEHOOK_GET_PREV(fn) (NULL)
#define BYTEHOOK_POP_STACK() do {} while (0)

#endif // STUB_BYTEHOOK_H
