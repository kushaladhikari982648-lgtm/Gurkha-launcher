package com.movtery.zalithlauncher.feature.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.*;
import android.os.*;
import android.view.PixelCopy;
import android.view.Surface;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.UUID;

/** Silent, bounded, gameplay-surface-only capture. NEVER falls back to Window/MediaProjection. */
public final class GameplayRecorder {
    public interface Listener { void onFinished(File video, String error); }
    private final Surface source;
    private final File folder;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("GameplayRecorder");
    private final Handler main = new Handler(Looper.getMainLooper());
    private Handler worker;
    private MediaCodec codec;
    private MediaMuxer muxer;
    private EncoderCanvas canvas;
    private Surface input;
    private Bitmap bitmap;
    private File partial, output;
    private boolean muxing, closed, copying;
    private volatile boolean stopping;
    private int track=-1, frames, misses;
    private long startTime;
    private String error;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    public static File directory(Context context) {
        File root=context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if(root==null) throw new IllegalStateException("Recording storage is unavailable");
        File dir=new File(root,"Recordings");
        if(!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Cannot create recordings folder");
        return dir;
    }
    public GameplayRecorder(Context context, Surface source, int width, int height, Listener listener) {
        if(source==null || !source.isValid()) throw new IllegalStateException("Game surface is not ready");
        this.source=source; this.listener=listener; this.folder=directory(context);
        thread.start(); worker=new Handler(thread.getLooper());
        worker.post(() -> { try { prepare(width,height); nextFrame(); } catch(Exception e) { fail(e); } });
    }
    public void stop() {
        stopping=true;
        worker.post(() -> { if(!copying) finish(); });
    }
    private void prepare(int originalWidth,int originalHeight) throws Exception {
        if(folder.getUsableSpace()<20L*1024*1024) throw new IllegalStateException("At least 20 MB free space is required");
        int[] size=MediaPolicy.recordingSize(originalWidth,originalHeight);
        Exception last=null;
        for(MediaCodecInfo candidate:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if(!candidate.isEncoder()) continue;
            try {
                MediaCodecInfo.CodecCapabilities caps=candidate.getCapabilitiesForType("video/avc");
                boolean surface=false;
                for(int format:caps.colorFormats) if(format==MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) surface=true;
                if(!surface) continue;
                MediaCodecInfo.VideoCapabilities video=caps.getVideoCapabilities();
                int w=size[0]/video.getWidthAlignment()*video.getWidthAlignment();
                int h=size[1]/video.getHeightAlignment()*video.getHeightAlignment();
                if(w<2 || h<2 || !video.areSizeAndRateSupported(w,h,15)) continue;
                codec=MediaCodec.createByCodecName(candidate.getName());
                MediaFormat format=MediaFormat.createVideoFormat("video/avc",w,h);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE,video.getBitrateRange().clamp(1500000));
                format.setInteger(MediaFormat.KEY_FRAME_RATE,15); format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,2);
                codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
                input=codec.createInputSurface(); codec.start();
                canvas=new EncoderCanvas(input,w,h);
                bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
                break;
            } catch(Exception e) {
                last=e;
                if(canvas!=null) { canvas.close(); canvas=null; }
                if(input!=null) { input.release(); input=null; }
                if(codec!=null) { try { codec.release(); } catch(Exception ignored) {} codec=null; }
            }
        }
        if(codec==null || canvas==null || bitmap==null) throw new IllegalStateException("No compatible AVC surface encoder on this device",last);
        output=new File(folder,"Gurkha-"+System.currentTimeMillis()+"-"+UUID.randomUUID()+".mp4");
        partial=new File(output.getPath()+".partial");
        muxer=new MediaMuxer(partial.getPath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        startTime=System.nanoTime();
    }
    private void nextFrame() {
        if(closed) return;
        if(stopping) { finish(); return; }
        try {
            if(!source.isValid()) throw new IllegalStateException("Game surface was lost; recording stopped");
            if(System.nanoTime()-startTime>600_000_000_000L) { stopping=true; finish(); return; }
            if(frames%150==0 && folder.getUsableSpace()<8L*1024*1024) { stopping=true; error="Recording stopped: storage is almost full"; finish(); return; }
            drain(false);
            copying=true;
            PixelCopy.request(source,bitmap,result -> {
                copying=false;
                if(stopping) { finish(); return; }
                try {
                    if(result==PixelCopy.SUCCESS) {
                        misses=0;
                        canvas.draw(bitmap,System.nanoTime()-startTime); frames++;
                        drain(false);
                    } else if(result==PixelCopy.ERROR_SOURCE_NO_DATA && ++misses<30) {
                        // First frame may not have been presented yet. Keep just one request in flight.
                    } else throw new IllegalStateException("Gameplay-only capture is unavailable (PixelCopy "+result+"). No full-screen fallback was used.");
                    worker.postDelayed(this::nextFrame,67);
                } catch(Exception e) { fail(e); }
            },worker);
        } catch(Exception e) { copying=false; fail(e); }
    }
    private void drain(boolean eos) throws Exception {
        long deadline=System.nanoTime()+5_000_000_000L;
        while(true) {
            int index=codec.dequeueOutputBuffer(info,eos ? 10000 : 0);
            if(index==MediaCodec.INFO_TRY_AGAIN_LATER) {
                if(!eos) return;
                if(System.nanoTime()>deadline) throw new IllegalStateException("Encoder did not finish the video");
            } else if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if(muxing) throw new IllegalStateException("Encoder changed output format twice");
                track=muxer.addTrack(codec.getOutputFormat()); muxer.start(); muxing=true;
            } else if(index>=0) {
                try {
                    ByteBuffer buffer=codec.getOutputBuffer(index);
                    if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0 && info.size>0) {
                        if(!muxing || buffer==null) throw new IllegalStateException("Encoder output is not ready");
                        buffer.position(info.offset); buffer.limit(info.offset+info.size);
                        muxer.writeSampleData(track,buffer,info);
                    }
                } finally { codec.releaseOutputBuffer(index,false); }
                if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0) return;
            }
        }
    }
    private void fail(Exception e) { error=e.getMessage()==null ? e.getClass().getSimpleName() : e.getMessage(); stopping=true; finish(); }
    private void finish() {
        if(closed || copying) return;
        closed=true;
        boolean complete=false;
        try {
            if(codec!=null && frames>0 && muxer!=null) {
                codec.signalEndOfInputStream(); drain(true);
                if(muxing) { muxer.stop(); muxing=false; complete=true; }
            }
        } catch(Exception e) { error="Cannot finalize recording: "+e.getMessage(); }
        finally {
            if(canvas!=null) { try { canvas.close(); } catch(Exception ignored) {} }
            if(codec!=null) { try { codec.stop(); } catch(Exception ignored) {} try { codec.release(); } catch(Exception ignored) {} }
            if(input!=null) input.release();
            if(muxer!=null) { try { muxer.release(); } catch(Exception ignored) {} }
            if(bitmap!=null) bitmap.recycle();
        }
        File saved=null;
        if(complete && partial!=null && partial.renameTo(output)) saved=output;
        else {
            if(partial!=null) partial.delete();
            if(error==null) error="No video was saved (no frames or storage unavailable)";
        }
        File result=saved; String message=error;
        main.post(() -> listener.onFinished(result,message));
        thread.quitSafely();
    }
}
