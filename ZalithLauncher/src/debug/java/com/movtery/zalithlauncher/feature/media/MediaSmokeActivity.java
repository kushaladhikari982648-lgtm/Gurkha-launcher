package com.movtery.zalithlauncher.feature.media;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileWriter;

/** Debug/shell only: actual encoder + muxer + decoding test, not a Minecraft benchmark. */
public final class MediaSmokeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private GameplayRecorder recorder;
    private Surface source;
    private File result;
    private boolean done, started, ownsSurface;
    private int frame;
    private final Runnable draw = new Runnable() {
        @Override public void run() {
            if(done || source == null || !source.isValid()) return;
            try {
                Canvas canvas = source.lockCanvas(null);
                try { canvas.drawColor((frame++ / 5) % 2 == 0 ? Color.RED : Color.GREEN); }
                finally { source.unlockCanvasAndPost(canvas); }
                handler.postDelayed(this, 60);
            } catch(Exception e) { report("FAIL draw: " + e); }
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String mode = getIntent().getStringExtra("surface");
        boolean texture = "texture".equals(mode);
        result = new File(getFilesDir(), "media-smoke-" + (texture ? "texture" : "surface") + ".txt");
        result.delete();
        FrameLayout root = new FrameLayout(this);
        if(texture) {
            TextureView view = new TextureView(this);
            view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) {
                    source = new Surface(t); ownsSurface = true; begin();
                }
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {
                    if(recorder != null) recorder.stop();
                    return true;
                }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) {}
                @Override public void onSurfaceTextureUpdated(SurfaceTexture t) {}
            });
            root.addView(view, new FrameLayout.LayoutParams(640,360));
        } else {
            SurfaceView view = new SurfaceView(this);
            view.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder holder) {}
                @Override public void surfaceChanged(SurfaceHolder holder, int f, int w, int h) { source = holder.getSurface(); begin(); }
                @Override public void surfaceDestroyed(SurfaceHolder holder) { if(recorder != null) recorder.stop(); }
            });
            root.addView(view, new FrameLayout.LayoutParams(640,360));
        }
        // Opaque magenta Android overlay covers the entire synthetic game view.
        // Its color MUST NOT appear in the encoded game-only video.
        View controls = new View(this); controls.setBackgroundColor(Color.MAGENTA);
        root.addView(controls, new FrameLayout.LayoutParams(640,360));
        setContentView(root);
        handler.postDelayed(() -> { if(!done) { report("FAIL capture timed out"); if(recorder!=null) recorder.stop(); } }, 25000);
    }
    private void begin() {
        if(started || done) return;
        started = true;
        draw.run();
        handler.postDelayed(() -> {
            if(done) return;
            try {
                recorder = new GameplayRecorder(this, source, 640, 360, (file, error) -> {
                    recorder = null;
                    if(done) return;
                    if(error != null || file == null) { report("FAIL recorder: " + error); return; }
                    new Thread(() -> validate(file), "ValidateRecording").start();
                });
                handler.postDelayed(() -> { if(recorder != null) recorder.stop(); }, 3500);
            } catch(Exception e) { report("FAIL start: " + e); }
        }, 300);
    }
    private void validate(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            int w = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            long duration = Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            if(w > 854 || h > 480 || w < 2 || h < 2 || duration < 1000) throw new AssertionError("Invalid dimensions/duration: " + w + "x" + h + " / " + duration);
            for(long time : new long[]{0, 1000000, 2000000}) {
                Bitmap bitmap = retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST);
                if(bitmap == null) throw new AssertionError("Cannot decode frame at " + time);
                int color = bitmap.getPixel(bitmap.getWidth()/2, bitmap.getHeight()/2);
                bitmap.recycle();
                boolean red = Color.red(color)>150 && Color.green(color)<90 && Color.blue(color)<90;
                boolean green = Color.green(color)>150 && Color.red(color)<90 && Color.blue(color)<90;
                if(!red && !green) throw new AssertionError("Game pixel missing or overlay leaked: " + Integer.toHexString(color));
            }
            runOnUiThread(() -> report("PASS actual PixelCopy/AVC/MP4 decode; three game-only frames; opaque magenta overlay excluded; " + w + "x" + h + " / " + duration + "ms"));
        } catch(Throwable e) { runOnUiThread(() -> report("FAIL decode: " + e)); }
        finally { try { retriever.release(); } catch(Exception ignored) {} file.delete(); }
    }
    private void report(String message) {
        if(done) return;
        done = true; handler.removeCallbacks(draw);
        try(FileWriter writer = new FileWriter(result)) { writer.write(message + "\n"); }
        catch(Exception e) { android.util.Log.e("MediaSmoke", "Cannot write result", e); }
        android.util.Log.i("MediaSmoke", message);
    }
    @Override public void onPause() { if(recorder != null) recorder.stop(); super.onPause(); }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if(recorder != null) recorder.stop();
        if(ownsSurface && source != null) source.release();
        super.onDestroy();
    }
}
