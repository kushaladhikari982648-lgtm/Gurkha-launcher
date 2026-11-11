package com.movtery.zalithlauncher.feature.media;

import android.graphics.Bitmap;
import android.opengl.*;
import android.view.Surface;
import java.nio.*;

/** Dedicated GLES context that uploads ONLY the PixelCopy game bitmap to the encoder. */
final class EncoderCanvas implements AutoCloseable {
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface target = EGL14.EGL_NO_SURFACE;
    private int program, texture, position, uv;
    private final int width, height;
    private final FloatBuffer vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    EncoderCanvas(Surface input, int width, int height) {
        this.width = width; this.height = height;
        vertices.put(new float[]{-1,-1,0,1, 1,-1,1,1, -1,1,0,0, 1,1,1,0}).position(0);
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            check(EGL14.eglInitialize(display, version, 0, version, 1), "EGL initialization");
            int[] attributes = {EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
                EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,0x3142,1,EGL14.EGL_NONE};
            EGLConfig[] config = new EGLConfig[1]; int[] count = new int[1];
            check(EGL14.eglChooseConfig(display, attributes, 0, config, 0, 1, count, 0) && count[0] > 0, "Recordable EGL config");
            context = EGL14.eglCreateContext(display, config[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
            target = EGL14.eglCreateWindowSurface(display, config[0], input, new int[]{EGL14.EGL_NONE},0);
            check(context != EGL14.EGL_NO_CONTEXT && target != EGL14.EGL_NO_SURFACE, "Encoder EGL surface");
            check(EGL14.eglMakeCurrent(display,target,target,context), "Encoder context");
            int vs = shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 p; attribute vec2 t; varying vec2 v; void main(){ gl_Position=vec4(p,0.,1.); v=t; }");
            int fs;
            try { fs = shader(GLES20.GL_FRAGMENT_SHADER,"precision mediump float; varying vec2 v; uniform sampler2D image; void main(){ gl_FragColor=texture2D(image,v); }"); }
            catch (RuntimeException e) { GLES20.glDeleteShader(vs); throw e; }
            program=GLES20.glCreateProgram(); GLES20.glAttachShader(program,vs); GLES20.glAttachShader(program,fs);
            GLES20.glLinkProgram(program); GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs);
            int[] ok=new int[1]; GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0); check(ok[0]!=0,"Encoder shader link");
            position=GLES20.glGetAttribLocation(program,"p"); uv=GLES20.glGetAttribLocation(program,"t");
            int[] textures=new int[1]; GLES20.glGenTextures(1,textures,0); texture=textures[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,width,height,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
        } catch (RuntimeException e) { close(); throw e; }
    }
    private static void check(boolean ok,String operation) { if(!ok) throw new IllegalStateException(operation+" failed"); }
    private static int shader(int kind,String source) {
        int id=GLES20.glCreateShader(kind); GLES20.glShaderSource(id,source); GLES20.glCompileShader(id);
        int[] ok=new int[1]; GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0) { String error=GLES20.glGetShaderInfoLog(id); GLES20.glDeleteShader(id); throw new IllegalStateException(error); }
        return id;
    }
    void draw(Bitmap bitmap,long timeNanos) {
        GLES20.glViewport(0,0,width,height); GLES20.glUseProgram(program); GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,bitmap);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"image"),0);
        vertices.position(0); GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,16,vertices);
        vertices.position(2); GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,vertices);
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(uv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        check(GLES20.glGetError()==GLES20.GL_NO_ERROR,"Encoder frame upload");
        check(EGLExt.eglPresentationTimeANDROID(display,target,timeNanos),"Encoder timestamp");
        check(EGL14.eglSwapBuffers(display,target),"Encoder frame submit");
    }
    @Override public void close() {
        if (display == EGL14.EGL_NO_DISPLAY) return;
        if(context != EGL14.EGL_NO_CONTEXT && target != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display,target,target,context);
            if(texture!=0) GLES20.glDeleteTextures(1,new int[]{texture},0);
            if(program!=0) GLES20.glDeleteProgram(program);
        }
        EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
        if(target != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,target);
        if(context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context);
        EGL14.eglReleaseThread();
        // Do NOT eglTerminate: the game/HWUI may use the same process-wide display.
        display=EGL14.EGL_NO_DISPLAY; context=EGL14.EGL_NO_CONTEXT; target=EGL14.EGL_NO_SURFACE;
    }
}
