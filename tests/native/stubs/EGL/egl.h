#ifndef TEST_EGL_H
#define TEST_EGL_H
#include <stdint.h>
typedef int EGLint;
typedef unsigned EGLBoolean;
typedef unsigned EGLenum;
typedef void *EGLDisplay, *EGLContext, *EGLSurface, *EGLConfig, *NativeDisplayType, *NativeWindowType;
typedef void (*__eglMustCastToProperFunctionPointerType)(void);
#define EGL_TRUE 1
#define EGL_FALSE 0
#define EGL_NO_DISPLAY ((void*)0)
#define EGL_NO_CONTEXT ((void*)0)
#define EGL_NO_SURFACE ((void*)0)
#define EGL_DEFAULT_DISPLAY ((void*)0)
#define EGL_NONE 0x3038
#define EGL_SUCCESS 0x3000
#define EGL_NOT_INITIALIZED 0x3001
#define EGL_BAD_ALLOC 0x3003
#define EGL_BAD_ATTRIBUTE 0x3004
#define EGL_BAD_CONFIG 0x3005
#define EGL_BAD_CONTEXT 0x3006
#define EGL_BAD_NATIVE_WINDOW 0x300B
#define EGL_BAD_SURFACE 0x300D
#define EGL_CONTEXT_LOST 0x300E
#define EGL_BLUE_SIZE 0x3022
#define EGL_GREEN_SIZE 0x3023
#define EGL_RED_SIZE 0x3024
#define EGL_ALPHA_SIZE 0x3021
#define EGL_DEPTH_SIZE 0x3025
#define EGL_SURFACE_TYPE 0x3033
#define EGL_WINDOW_BIT 4
#define EGL_PBUFFER_BIT 1
#define EGL_RENDERABLE_TYPE 0x3040
#define EGL_OPENGL_BIT 8
#define EGL_OPENGL_ES_BIT 1
#define EGL_OPENGL_ES2_BIT 4
#define EGL_OPENGL_ES3_BIT_KHR 64
#define EGL_NATIVE_VISUAL_ID 0x302E
#define EGL_CONTEXT_CLIENT_VERSION 0x3098
#define EGL_OPENGL_API 0x30A2
#define EGL_OPENGL_ES_API 0x30A0
#define EGL_WIDTH 0x3057
#define EGL_HEIGHT 0x3056
#endif
