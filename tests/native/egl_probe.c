/* Executes the production EGL loader, GL context state machine and window slot. */
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <pthread.h>
#include <dlfcn.h>
#include <android/native_window.h>
#include <environ/environ.h>
#include "ctxbridges/gl_bridge.h"
#include "ctxbridges/egl_loader.h"
#include "ctxbridges/window_slot.h"
#include "ctxbridges/context_policy.h"

struct test_environ globals;
struct test_environ *pojav_environ = &globals;
__eglMustCastToProperFunctionPointerType (*eglGetProcAddress_p)(const char *);
static const char *mode;
static int opens, creates, swaps, destroyed, error;
static EGLint requested_type, requested_es;
static ANativeWindow a, b;

int __android_log_print(int p, const char *tag, const char *fmt, ...) {
    (void)p; (void)tag;
    va_list args; va_start(args, fmt); vfprintf(stderr, fmt, args); va_end(args); fputc('\n',stderr); return 0;
}
void ANativeWindow_acquire(ANativeWindow *w) { assert(w && atomic_fetch_add(&w->refs,1)>=0); }
void ANativeWindow_release(ANativeWindow *w) { assert(w && atomic_fetch_sub(&w->refs,1)>0); }
int ANativeWindow_setBuffersGeometry(ANativeWindow *w,int x,int y,int f) { (void)x;(void)y;(void)f; assert(w && w->refs>0); return 0; }
void *test_dlopen(const char *name,int flags) {
    ++opens; assert(flags & RTLD_NOW); assert(strcmp(name,"/verified/libltw.so")==0);
    return strcmp(mode,"missing-provider")==0 ? NULL : (void*)1;
}
int test_dlclose(void *handle) { assert(handle); return 0; }
char *test_dlerror(void) { return "deliberately missing provider"; }
static EGLDisplay display(NativeDisplayType n) { (void)n; return (void*)1; }
static EGLBoolean initialize(EGLDisplay d,EGLint*a,EGLint*b) { (void)d;(void)a;(void)b; return 1; }
static EGLBoolean bind(EGLenum api) { assert(api==EGL_OPENGL_ES_API); return strcmp(mode,"bind-failure")!=0; }
static EGLBoolean choose(EGLDisplay d,const EGLint *attrs,EGLConfig *out,EGLint size,EGLint *count) {
    (void)d; assert(size==1);
    for(;attrs[0]!=EGL_NONE;attrs+=2) if(attrs[0]==EGL_RENDERABLE_TYPE) requested_type=attrs[1];
    *count=strcmp(mode,"no-config")==0 ? 0:1; *out=(void*)2; return 1;
}
static EGLBoolean visual(EGLDisplay d,EGLConfig c,EGLint attr,EGLint*out) { (void)d;(void)c;(void)attr;*out=1;return 1; }
static EGLContext create(EGLDisplay d,EGLConfig c,EGLContext shared,const EGLint *attrs) {
    (void)d;(void)c;(void)shared; ++creates; requested_es=attrs[1];
    return strcmp(mode,"context-failure")==0 ? NULL : (void*)3;
}
static EGLBoolean current(EGLDisplay d,EGLSurface draw,EGLSurface read,EGLContext ctx) {
    (void)d;(void)read;
    if(!ctx) return 1;
    assert(draw); return strcmp(mode,"current-failure")!=0;
}
static EGLSurface window(EGLDisplay d,EGLConfig c,NativeWindowType w,const EGLint *attrs) {
    (void)d;(void)c;(void)attrs; assert(((ANativeWindow*)w)->refs>0); return (void*)4;
}
static EGLSurface pbuffer(EGLDisplay d,EGLConfig c,const EGLint*a) { (void)d;(void)c;(void)a;return (void*)5; }
static EGLBoolean swap(EGLDisplay d,EGLSurface s) {
    (void)d; assert(s); ++swaps;
    if(strcmp(mode,"lost-context")==0){error=EGL_CONTEXT_LOST;return 0;} return 1;
}
static EGLBoolean destroy(EGLDisplay d,EGLSurface s) { (void)d; assert(s);++destroyed;return 1; }
static EGLint get_error(void) { return error ? error : EGL_BAD_CONTEXT; }
static EGLBoolean success(void) { return 1; }
void *GLGetProcAddress(void *h,const char *name) {
    assert(h);
    if(strcmp(mode,"missing-symbol")==0 && strcmp(name,"eglMakeCurrent")==0) return NULL;
#define FN(n,f) if(!strcmp(name,n)) return (void*)f
    FN("eglGetDisplay",display);FN("eglInitialize",initialize);FN("eglBindAPI",bind);FN("eglChooseConfig",choose);
    FN("eglGetConfigAttrib",visual);FN("eglCreateContext",create);FN("eglMakeCurrent",current);
    FN("eglCreateWindowSurface",window);FN("eglCreatePbufferSurface",pbuffer);FN("eglSwapBuffers",swap);
    FN("eglGetError",get_error);FN("eglDestroySurface",destroy);FN("eglDestroyContext",destroy);
    return (void*)success;
}
static void *publish_many(void *unused) {
    (void)unused;
    for(int i=0;i<10000;i++) { ANativeWindow_acquire(&a); bridge_publish_window(&a); }
    return NULL;
}
int main(int argc,char **argv) {
    assert(argc==2); mode=argv[1]; setenv("POJAVEXEC_EGL","/verified/libltw.so",1);setenv("LIBGL_ES","3",1);
    int es;
    assert(bridge_es_version(NULL,&es) && es==2);
    assert(!bridge_es_version("3_ltw",&es));assert(!bridge_es_version("0",&es));assert(!bridge_es_version("4",&es));
    if(!strncmp(mode,"missing-",8)) { assert(!gl_init());assert(opens==1);assert(!eglMakeCurrent_p);puts("PASS fail-closed loader");return 0; }
    assert(gl_init());
    if(!strcmp(mode,"invalid-es")) setenv("LIBGL_ES","3_ltw",1);
    gl_render_window_t *ctx=gl_init_context(NULL);
    if(!strcmp(mode,"bind-failure") || !strcmp(mode,"no-config") || !strcmp(mode,"invalid-es")) {
        assert(!ctx && creates==0);puts("PASS context rejected before create");return 0;
    }
    assert(requested_type==EGL_OPENGL_ES3_BIT_KHR && requested_es==3);
    if(!strcmp(mode,"context-failure")) {assert(!ctx);puts("PASS failed context");return 0;}
    assert(ctx);
    ANativeWindow_acquire(&a);bridge_publish_window(&a);
    gl_make_current(ctx);
    if(!strcmp(mode,"current-failure")) { assert(!gl_get_current());gl_swap_buffers();assert(swaps==0);puts("PASS no swap after failed make-current");return 0; }
    assert(gl_get_current()==ctx);gl_swap_buffers();
    if(!strcmp(mode,"lost-context")) { assert(!gl_get_current());gl_swap_buffers();assert(swaps==1);puts("PASS lost context is not used");return 0; }
    ANativeWindow_acquire(&b);bridge_publish_window(&b);gl_swap_buffers();assert(a.refs==0 && b.refs==2);
    bridge_publish_window(NULL);gl_swap_buffers();assert(b.refs==0 && ctx->surface==(void*)5);
    bridge_publish_window(NULL);gl_swap_buffers();
    pthread_t thread;assert(!pthread_create(&thread,NULL,publish_many,NULL));
    for(int i=0;i<10000;i++) { ANativeWindow *w=bridge_acquire_window(NULL);if(w) ANativeWindow_release(w); }
    pthread_join(thread,NULL);bridge_publish_window(NULL);gl_swap_buffers();
    gl_terminate();assert(!gl_get_current());assert(a.refs==0 && b.refs==0);assert(destroyed>0);
    puts("PASS real bridge config/current/swap/detach/window ownership");
}
