/* Host-only GL mock. No GPU compatibility is implied by these tests. */
#include <assert.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
typedef unsigned GLenum, GLuint;
typedef unsigned char GLubyte;
typedef int GLint, GLsizei;
#define APIENTRY_GL4ES
#define AliasExport(...)
#define GL_INVALID_VALUE 0x501
#define GL_INVALID_ENUM 0x500
#define GL_INVALID_OPERATION 0x502
#define GL_NUM_EXTENSIONS 0x821D
#define GL_EXTENSIONS 0x1F03
#define GL_DRAW_INDIRECT_BUFFER_BINDING 0x8F43
#define GL_ELEMENT_ARRAY_BUFFER 0x8893
#define GL_UNSIGNED_BYTE 0x1401
#define GL_UNSIGNED_SHORT 0x1403
#define GL_UNSIGNED_INT 0x1405
#define GL_POINTS 0
static int last_error, supported = 1, indirect_binding = 5, calls;
static int available = 1, extension_queries, extension_strings, resolutions;
#include "../native/pv_context_cache.h"
static void *gles = (void *)1;
typedef struct { int unused; } scratch_t;
static struct { GLuint real_buffer; } elements = {4};
static struct { __typeof__(elements) *elements; } vao = {&elements};
static struct { struct pv_mdi_cache pv_mdi; struct { int pending, active; } list; __typeof__(vao) *vao; } state = {{0}, {0,0}, &vao};
static __typeof__(state) *glstate = &state;
static void errorShim(GLenum error) { last_error = error; }
static void gl4es_flush(void) { state.list.pending = 0; state.list.active = 0; }
static void realize_glenv(int p, int a, int b, GLenum t, const void *i, scratch_t *s) {
    (void)p; (void)a; (void)b; (void)t; (void)i; (void)s;
}
static void free_scratch(scratch_t *s) { (void)s; }
static void realize_textures(int n) { (void)n; }
static void bindBuffer(GLenum t, GLuint b) { assert(t == GL_ELEMENT_ARRAY_BUFFER); assert(b == 4); }
static void get(GLenum name, GLint *out) {
    if (name == GL_NUM_EXTENSIONS) ++extension_queries;
    *out = name == GL_NUM_EXTENSIONS ? 1 : indirect_binding;
}
static const GLubyte *stringi(GLenum name, GLuint i) {
    (void)name; (void)i; ++extension_strings;
    return (const GLubyte *)(supported ? "GL_EXT_multi_draw_indirect" : "GL_EXT_multi_draw_indirect_extra");
}
static void arrays(GLenum m, const void *p, GLsizei n, GLsizei s) {
    (void)m; (void)p; assert(n == 2); assert(s == 0); ++calls;
}
static void indexed(GLenum m, GLenum t, const void *p, GLsizei n, GLsizei s) {
    assert(t == GL_UNSIGNED_SHORT); arrays(m, p, n, s);
}
static void *proc_address(void *lib, const char *name) {
    assert(lib == gles); ++resolutions;
    if (!strcmp(name, "glGetIntegerv")) return (void *)get;
    if (!strcmp(name, "glGetStringi")) return (void *)stringi;
    if (!available) return NULL;
    if (!strcmp(name, "glMultiDrawElementsIndirectEXT")) return (void *)indexed;
    if (!strcmp(name, "glMultiDrawArraysIndirectEXT")) return (void *)arrays;
    return NULL;
}
static void *(*egl_eglGetProcAddress)(const char *) = NULL;
#define LOAD_EGL(name) ((void)0)
#include "../native/ltw_multidraw.inc"
int main(void) {
    assert(pv_mdi_validate(0, 2, 0, 20) == 0);
    assert(pv_mdi_validate(0, 2, 4, 20) == 0);
    assert(pv_mdi_validate(0, -1, 0, 20) == GL_INVALID_VALUE);
    assert(pv_mdi_validate(0, 1, 3, 20) == GL_INVALID_VALUE);
    assert(pv_mdi_validate((void *)2, 1, 0, 20) == GL_INVALID_VALUE);
    assert(pv_mdi_validate((void *)(UINTPTR_MAX - 3), 2, 0, 20) == GL_INVALID_VALUE);
    gl4es_glMultiDrawArraysIndirect(4, 0, 2, 0);
    assert(calls == 1 && last_error == 0);
    gl4es_glMultiDrawElementsIndirect(4, GL_UNSIGNED_SHORT, 0, 2, 0);
    assert(calls == 2);
    memset(&state.pv_mdi, 0, sizeof(state.pv_mdi)); supported = 0;
    gl4es_glMultiDrawArraysIndirect(4, 0, 2, 0);
    assert(calls == 2 && last_error == GL_INVALID_OPERATION);
    memset(&state.pv_mdi, 0, sizeof(state.pv_mdi)); supported = 1; last_error = 0; indirect_binding = 0;
    gl4es_glMultiDrawArraysIndirect(4, 0, 2, 0);
    assert(calls == 2 && last_error == GL_INVALID_OPERATION);
    memset(&state.pv_mdi, 0, sizeof(state.pv_mdi)); indirect_binding = 5; available = 0; last_error = 0;
    gl4es_glMultiDrawArraysIndirect(4, 0, 2, 0);
    assert(calls == 2 && last_error == GL_INVALID_OPERATION);
    memset(&state.pv_mdi, 0, sizeof(state.pv_mdi)); available = 1; last_error = 0;
    gl4es_glMultiDrawArraysIndirect(4, 0, 0, 0);
    assert(calls == 2 && last_error == 0);
    state.list.active = 1;
    gl4es_glMultiDrawArraysIndirect(4, 0, 2, 0);
    assert(calls == 2 && last_error == GL_INVALID_OPERATION);
    state.list.pending = 1; last_error = 0;
    gl4es_glMultiDrawArraysIndirect(4, 0, 2, 0);
    assert(calls == 3 && !last_error);
    /* Steady-state draws must not rescan extensions or resolve any symbols. */
    int queries_before = extension_queries, strings_before = extension_strings;
    int resolutions_before = resolutions, calls_before = calls;
    for (int i = 0; i < 10000; ++i)
        gl4es_glMultiDrawArraysIndirect(4, 0, 2, 0);
    assert(calls == calls_before + 10000);
    assert(extension_queries == queries_before);
    assert(extension_strings == strings_before);
    assert(resolutions == resolutions_before);
    /* Different context: unsupported capability must not leak from state. */
    __typeof__(state) second = {{0}, {0,0}, &vao};
    glstate = &second; supported = 0; last_error = 0;
    gl4es_glMultiDrawArraysIndirect(4, 0, 2, 0);
    assert(last_error == GL_INVALID_OPERATION);
    assert(extension_queries == queries_before + 1);
    glstate = &state; supported = 1; last_error = 0;
    gl4es_glMultiDrawArraysIndirect(4, 0, 2, 0);
    assert(!last_error && extension_queries == queries_before + 1);
    return 0;
}
