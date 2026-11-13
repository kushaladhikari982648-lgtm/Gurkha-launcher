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

void* bytehook_get_prev_func(void* func);
void bytehook_pop_stack(void* return_address);

#define BYTEHOOK_CALL_PREV(func, func_sig, ...) \
    ((func_sig)(bytehook_get_prev_func((void*)(func))))(__VA_ARGS__)
#define BYTEHOOK_POP_STACK() \
    do { bytehook_pop_stack(__builtin_return_address(0)); } while (0)

#endif // STUB_BYTEHOOK_H
