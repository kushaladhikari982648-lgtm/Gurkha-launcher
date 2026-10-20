/* SPDX-License-Identifier: LGPL-3.0-only
 * Derived from MojoLauncher/LTW ltw/src/main/tinywrapper/multidraw.c
 * Copyright (c) 2025 artDev, SerpentSpirale, CADIndie.
 * Adaptation (c) 2026 Powervr-the-poop contributors.
 *
 * Integrated into Krypton's drawing.c: ONE GL namespace, context and state
 * tracker. No LTW shared library or LTW context_t is loaded.
 * Adds LTW's native EXT multi-draw-indirect forwarding with explicit errors
 * instead of LTW's silent unsupported no-op. No extension is advertised here.
 */
#include <stdint.h>
#include <string.h>

typedef void (*pv_mdi_elements_fn)(GLenum, GLenum, const void *, GLsizei, GLsizei);
typedef void (*pv_mdi_arrays_fn)(GLenum, const void *, GLsizei, GLsizei);
typedef const GLubyte *(*pv_stringi_fn)(GLenum, GLuint);
typedef void (*pv_integer_fn)(GLenum, GLint *);

/* Use Krypton's host resolver, not RTLD_DEFAULT. Capability and entry points
 * are cached in the owning GL context, never a process-global static. */
static void *pv_host_proc(const char *name) {
    if (!gles) return NULL;
    void *fn = proc_address(gles, name);
    if (!fn) {
        LOAD_EGL(eglGetProcAddress);
        if (egl_eglGetProcAddress) fn = (void *)egl_eglGetProcAddress(name);
    }
    return fn;
}

static int pv_has_mdi(void) {
    struct pv_mdi_cache *cache = &glstate->pv_mdi;
    if (cache->initialized) return cache->supported;
    /* Do not poison the cache if the host library is not loaded yet. */
    if (!gles) return 0;
    pv_integer_fn get = (pv_integer_fn)pv_host_proc("glGetIntegerv");
    pv_stringi_fn stringi = (pv_stringi_fn)pv_host_proc("glGetStringi");
    if (!get || !stringi) return 0;
    cache->get_integer = (void *)get;
    GLint count = 0;
    get(GL_NUM_EXTENSIONS, &count);
    for (GLint i = 0; i < count; ++i) {
        const char *ext = (const char *)stringi(GL_EXTENSIONS, (GLuint)i);
        if (ext && strcmp(ext, "GL_EXT_multi_draw_indirect") == 0) {
            cache->supported = 1;
            break;
        }
    }
    if (cache->supported) {
        cache->elements = pv_host_proc("glMultiDrawElementsIndirectEXT");
        cache->arrays = pv_host_proc("glMultiDrawArraysIndirectEXT");
    }
    cache->initialized = 1;
    return cache->supported;
}

static int pv_mdi_validate(const void *offset, GLsizei draws, GLsizei stride,
                           unsigned command_bytes) {
    if (draws < 0 || stride < 0 || (stride % 4)) return GL_INVALID_VALUE;
    if ((uintptr_t)offset % 4) return GL_INVALID_VALUE;
    /* Validate arithmetic before passing offsets to a driver. Overlapping
     * commands (stride < command size) are legal; don't reject them here. */
    uintptr_t step = stride ? (uintptr_t)stride : command_bytes;
    if (draws > 0 && ((uintptr_t)offset > UINTPTR_MAX - command_bytes ||
        (uintptr_t)(draws - 1) >
        (UINTPTR_MAX - (uintptr_t)offset - command_bytes) / step))
        return GL_INVALID_VALUE;
    return 0;
}

static int pv_mdi_ready(const void *offset, GLsizei draws, GLsizei stride,
                        unsigned command_bytes) {
    int error = pv_mdi_validate(offset, draws, stride, command_bytes);
    if (error) { errorShim(error); return 0; }
    if (!draws) return 0;
    if (glstate->list.pending) gl4es_flush();
    if (glstate->list.active) { errorShim(GL_INVALID_OPERATION); return 0; }
    if (!pv_has_mdi()) { errorShim(GL_INVALID_OPERATION); return 0; }
    pv_integer_fn get = (pv_integer_fn)glstate->pv_mdi.get_integer;
    GLint binding = 0;
    get(GL_DRAW_INDIRECT_BUFFER_BINDING, &binding);
    if (!binding) { errorShim(GL_INVALID_OPERATION); return 0; }
    return 1;
}

void APIENTRY_GL4ES gl4es_glMultiDrawElementsIndirect(GLenum mode, GLenum type,
        const void *indirect, GLsizei drawcount, GLsizei stride) {
    if (type != GL_UNSIGNED_BYTE && type != GL_UNSIGNED_SHORT && type != GL_UNSIGNED_INT) {
        errorShim(GL_INVALID_ENUM); return;
    }
    if (!pv_mdi_ready(indirect, drawcount, stride, 20)) return;
    pv_mdi_elements_fn draw = (pv_mdi_elements_fn)
        glstate->pv_mdi.elements;
    if (!draw) { errorShim(GL_INVALID_OPERATION); return; }
    scratch_t scratch = {0};
    realize_glenv(mode == GL_POINTS, 0, 0, type, indirect, &scratch);
    free_scratch(&scratch);
    realize_textures(1);
    if (!glstate->vao->elements || !glstate->vao->elements->real_buffer) {
        errorShim(GL_INVALID_OPERATION); return;
    }
    bindBuffer(GL_ELEMENT_ARRAY_BUFFER, glstate->vao->elements->real_buffer);
    draw(mode, type, indirect, drawcount, stride);
}
AliasExport(void, glMultiDrawElementsIndirect, ,
    (GLenum mode, GLenum type, const void *indirect, GLsizei drawcount, GLsizei stride));
AliasExport(void, glMultiDrawElementsIndirect, ARB,
    (GLenum mode, GLenum type, const void *indirect, GLsizei drawcount, GLsizei stride));

void APIENTRY_GL4ES gl4es_glMultiDrawArraysIndirect(GLenum mode, const void *indirect,
        GLsizei drawcount, GLsizei stride) {
    if (!pv_mdi_ready(indirect, drawcount, stride, 16)) return;
    pv_mdi_arrays_fn draw = (pv_mdi_arrays_fn)
        glstate->pv_mdi.arrays;
    if (!draw) { errorShim(GL_INVALID_OPERATION); return; }
    scratch_t scratch = {0};
    realize_glenv(mode == GL_POINTS, 0, 0, 0, NULL, &scratch);
    free_scratch(&scratch);
    realize_textures(1);
    draw(mode, indirect, drawcount, stride);
}
AliasExport(void, glMultiDrawArraysIndirect, ,
    (GLenum mode, const void *indirect, GLsizei drawcount, GLsizei stride));
AliasExport(void, glMultiDrawArraysIndirect, ARB,
    (GLenum mode, const void *indirect, GLsizei drawcount, GLsizei stride));
