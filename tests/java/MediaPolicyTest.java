import com.movtery.zalithlauncher.feature.media.MediaPolicy;

public class MediaPolicyTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        for (String ext : new String[]{"mp4", "M4V", "WebM", "mkv", "3gp", "mov"}) check(MediaPolicy.isVideo("background." + ext));
        for (String name : new String[]{"a.mp4.exe", "a.png", "mp4", ""}) check(!MediaPolicy.isVideo(name));
        for (int w=64; w<=1024; w*=2) {
            check(MediaPolicy.validTexture(w,w,false));
            check(MediaPolicy.validTexture(w,w/2,false));
            check(MediaPolicy.validTexture(w,w/2,true));
            check(!MediaPolicy.validTexture(w,w,true));
        }
        for (int[] size : new int[][]{{0,0},{-1,64},{32,32},{65,65},{2048,2048},{64,48}})
            check(!MediaPolicy.validTexture(size[0],size[1],false));
        for (String name : new String[]{"Player_123", "A", "1234567890123456"}) check(MediaPolicy.validPlayerName(name));
        for (String name : new String[]{"../Player", "", "a/b", "a\\b", "player name", "12345678901234567", "a\n"}) check(!MediaPolicy.validPlayerName(name));
        for (int[] source : new int[][]{{1920,1080},{1280,720},{3840,2160},{640,360},{1080,1920},{2,2},{853,479}}) {
            int[] dest=MediaPolicy.recordingSize(source[0],source[1]);
            check(dest[0]<=854 && dest[1]<=480 && dest[0]<=source[0] && dest[1]<=source[1]);
            check(dest[0]>=2 && dest[1]>=2 && dest[0]%2==0 && dest[1]%2==0);
        }
        try { MediaPolicy.recordingSize(0,10); throw new AssertionError(); } catch (IllegalArgumentException expected) {}
        System.out.println("PASS: actual Java media policy");
    }
}
