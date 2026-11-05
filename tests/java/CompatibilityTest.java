package net.kdt.pojavlaunch.multirt;

import net.kdt.pojavlaunch.Architecture;
import net.kdt.pojavlaunch.utils.NativeLibraryInfo;
import net.kdt.pojavlaunch.utils.EglCapabilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class CompatibilityTest {
    public static void main(String[] args) throws Exception {
        assert EglCapabilities.canQueryEs3Configs(1, 5, false);
        assert EglCapabilities.canQueryEs3Configs(1, 4, true);
        assert !EglCapabilities.canQueryEs3Configs(1, 4, false);
        assert !EglCapabilities.canQueryEs3Configs(0, 0, false);
        android.os.Process.bits64 = false;
        assert Architecture.is64BitsDevice(); // 64-bit phone, 32-bit APK
        assert Architecture.getProcessArchitecture("/data/app/test/lib/arm") == Architecture.ARCH_ARM;
        assert Architecture.getAddressSpaceLimit() == Architecture.ADDRESS_SPACE_LIMIT_32_BIT;
        android.os.Process.bits64 = true;
        assert Architecture.getProcessArchitecture("/data/app/test/lib/arm64") == Architecture.ARCH_ARM64;
        assert Architecture.getAddressSpaceLimit() == Architecture.ADDRESS_SPACE_LIMIT_64_BIT;
        android.os.Process.bits64 = false;
        assert Architecture.getProcessArchitecture("/data/app/test/lib/x86") == Architecture.ARCH_X86;
        android.os.Process.bits64 = true;
        assert Architecture.getProcessArchitecture("/data/app/test/lib/x86_64") == Architecture.ARCH_X86_64;
        assert Architecture.archAsInt(null) == Architecture.UNSUPPORTED_ARCH;
        assert RuntimeSelection.majorVersion("1.8.0_442") == 8;
        assert RuntimeSelection.majorVersion("21.0.1") == 21;
        assert RuntimeSelection.majorVersion("25-ea") == 25;
        assert RuntimeSelection.majorVersion("25+35") == 25;
        assert RuntimeSelection.majorVersion("broken") == 0;
        assert RuntimeSelection.majorVersion("1") == 0;
        assert RuntimeSelection.majorVersion(null) == 0;
        List<Runtime> installed = Arrays.asList(
            new Runtime("broken"),
            new Runtime("arm8", "1.8.0", "aarch32", 8),
            new Runtime("arm21", "21.0.1", "arm", 21),
            new Runtime("arm25", "25.0.3", "arm", 25),
            new Runtime("arm64-21", "21.0.1", "aarch64", 21),
            new Runtime("arm64-25", "25.0.3", "aarch64", 25),
            new Runtime("x86-21", "21.0.1", "i586", 21),
            new Runtime("x64-25", "25.0.3", "amd64", 25));
        assert RuntimeSelection.select(installed, 21, Architecture.ARCH_ARM, "arm64-25").name.equals("arm21");
        assert RuntimeSelection.select(installed, 25, Architecture.ARCH_ARM, "arm8").name.equals("arm25");
        assert RuntimeSelection.select(installed, 25, Architecture.ARCH_ARM64, "broken").name.equals("arm64-25");
        assert RuntimeSelection.select(installed, 21, Architecture.ARCH_ARM, "arm25").name.equals("arm25");
        assert RuntimeSelection.select(installed, 25, Architecture.ARCH_X86, null) == null;
        assert RuntimeSelection.select(installed, 25, Architecture.ARCH_X86_64, null).name.equals("x64-25");
        assert RuntimeSelection.select(installed, 26, Architecture.ARCH_ARM64, null) == null;
        assert RuntimeSelection.select(installed, 8, Architecture.UNSUPPORTED_ARCH, null) == null;
        Path path = Files.createTempFile("native-abi-test", ".so");
        try {
            for (int[] abi : new int[][]{{Architecture.ARCH_ARM,1,40},{Architecture.ARCH_ARM64,2,183},
                    {Architecture.ARCH_X86,1,3},{Architecture.ARCH_X86_64,2,62}}) {
                byte[] elf = new byte[20];
                elf[0]=0x7f; elf[1]='E'; elf[2]='L'; elf[3]='F'; elf[4]=(byte)abi[1]; elf[5]=1; elf[18]=(byte)abi[2];
                Files.write(path, elf);
                assert NativeLibraryInfo.matches(path.toFile(), abi[0]);
                elf[4]=(byte)(abi[1]==1 ? 2 : 1);
                Files.write(path, elf);
                assert !NativeLibraryInfo.matches(path.toFile(), abi[0]);
            }
            Files.write(path, new byte[]{0x7f});
            assert !NativeLibraryInfo.matches(path.toFile(), Architecture.ARCH_ARM);
        } finally { Files.delete(path); }
        System.out.println("Process ABI, runtime selection and ELF compatibility tests passed");
    }
}
