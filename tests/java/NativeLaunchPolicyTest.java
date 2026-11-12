import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.*;
import net.kdt.pojavlaunch.utils.NativeLaunchPolicy;
import com.movtery.zalithlauncher.utils.file.CompactLogs;

public class NativeLaunchPolicyTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        List<String> vm = new ArrayList<>(Arrays.asList("-Xmx500M", "-Dorg.lwjgl.librarypath=/old", "-Dother=keep", "-Dorg.lwjgl.librarypath=/manifest", "-Dorg.lwjgl.librarypath"));
        Map<String,String> locked = new LinkedHashMap<>(); locked.put("org.lwjgl.librarypath", "/verified");
        NativeLaunchPolicy.enforceProperties(vm,locked);
        check(vm.equals(Arrays.asList("-Xmx500M", "-Dother=keep", "-Dorg.lwjgl.librarypath=/verified")));
        vm.add("net.minecraft.client.main.Main");vm.add("-Dorg.lwjgl.librarypath=game-data");
        check(vm.indexOf("-Dorg.lwjgl.librarypath=/verified") < vm.indexOf("net.minecraft.client.main.Main"));
        for(String version : new String[]{null,"3.4.2","3.5.0","4.0.0","3.4.3-custom"}) check(!NativeLaunchPolicy.supportsModern(version));
        check(NativeLaunchPolicy.supportsModern("3.4.1") && NativeLaunchPolicy.supportsModern("3.4.3"));
        File dir=Files.createTempDirectory("compact-logs").toFile();
        try {
            for(int length : new int[]{0,128,320*1024,8*1024*1024}) {
                File file=new File(dir,"log.txt");
                try(RandomAccessFile out=new RandomAccessFile(file,"rw")) {
                    out.setLength(length);
                    if(length>0){out.write("HEADER".getBytes(StandardCharsets.UTF_8));out.seek(length-4);out.write("TAIL".getBytes(StandardCharsets.UTF_8));}
                }
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(ZipOutputStream zip=new ZipOutputStream(bytes)){CompactLogs.add(file,"log.txt",zip);}
                try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                    check(zip.getNextEntry()!=null);
                    ByteArrayOutputStream content=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
                    while((count=zip.read(buffer))!=-1) content.write(buffer,0,count);
                    check(content.size()<=321*1024);
                    String text=new String(content.toByteArray(),StandardCharsets.UTF_8);
                    if(length>0) check(text.startsWith("HEADER") && text.endsWith("TAIL"));
                    if(length>320*1024) check(text.contains("Middle omitted"));
                }
                check(file.delete());
            }
        } finally {dir.delete();}
        System.out.println("PASS native argument ownership / explicit substitutions / bounded logs");
    }
}
