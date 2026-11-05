package net.kdt.pojavlaunch;

import android.os.Build;
import android.os.Process;
import java.io.File;

/**
 * This class aims at providing a simple and easy way to deal with the device architecture.
 */
public class Architecture {
	public static final int UNSUPPORTED_ARCH = -1;
	public static final int ARCH_ARM64 = 0x1;
	public static final int ARCH_ARM = 0x2;
	public static final int ARCH_X86 = 0x4;
	public static final int ARCH_X86_64 = 0x8;

	/* On both 32-bit ARM and x86, the top 1GB is reserved for kernel use. */
	public static final long ADDRESS_SPACE_LIMIT_32_BIT = 0xbfffffffL;
	/*
	 * Technically, this is supposed to be 48 bits on x86_64, but nobody's allocating
	 * 524288 terabytes of RAM on Pojav any time soon.
	 */
	public static final long ADDRESS_SPACE_LIMIT_64_BIT = 0x7fffffffffL;

	/**
	 * Get the highest byte accessible within the process's address space.
	 * @return the highest byte accessible within the process's address space.
	 */
	public static long getAddressSpaceLimit() {
		return is64BitProcess() ? ADDRESS_SPACE_LIMIT_64_BIT : ADDRESS_SPACE_LIMIT_32_BIT;
	}

    /** Native libraries and the embedded JVM must match the APK process, not the phone CPU. */
    public static boolean is64BitProcess() { return Process.is64Bit(); }

    public static int getProcessArchitecture(String nativeLibraryDir) {
        // Android's installed lib directory is authoritative, including native-bridge installations.
        int installed = nativeLibraryDir == null ? UNSUPPORTED_ARCH
                : archAsInt(new File(nativeLibraryDir).getName());
        if (installed != UNSUPPORTED_ARCH && is64BitArchitecture(installed) == is64BitProcess()) {
            return installed;
        }
        int reported = archAsInt(System.getProperty("os.arch", ""));
        if (reported != UNSUPPORTED_ARCH && is64BitArchitecture(reported) == is64BitProcess()) return reported;
        return isx86Device() ? (is64BitProcess() ? ARCH_X86_64 : ARCH_X86)
                : (is64BitProcess() ? ARCH_ARM64 : ARCH_ARM);
    }

    public static boolean is64BitArchitecture(int arch) {
        return arch == ARCH_ARM64 || arch == ARCH_X86_64;
    }

	/**
	 * Tell us if the device supports 64 bits architecture
	 * @return If the device supports 64 bits architecture
	 */
	public static boolean is64BitsDevice(){
		return Build.SUPPORTED_64_BIT_ABIS.length != 0;
	}

	/**
	 * Tell us if the device supports 32 bits architecture
	 * Note, that a 64 bits device won't be reported as supporting 32 bits.
	 * @return If the device supports 32 bits architecture
	 */
	public static boolean is32BitsDevice(){
		return !is64BitsDevice();
	}

	/**
	 * Tells the device supported architecture.
	 * Since mips(/64) has been phased out long ago, is isn't checked here.
	 *
	 * @return ARCH_ARM || ARCH_ARM64 || ARCH_X86 || ARCH_86_64
	 */
	public static int getDeviceArchitecture(){
		if(isx86Device()){
			return is64BitsDevice() ? ARCH_X86_64 : ARCH_X86;
		}
		return is64BitsDevice() ? ARCH_ARM64 : ARCH_ARM;
	}

	/**
	 * Tell is the device is based on an x86 processor.
	 * It doesn't tell if the device is 64 or 32 bits.
	 * @return Whether or not the device is x86 based.
	 */
	public static boolean isx86Device(){
		//We check the whole range of supported ABIs,
		//Since asus zenfones can place arm before their native instruction set.
		String[] ABI = is64BitsDevice() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
		int comparedArch = is64BitsDevice() ? ARCH_X86_64 : ARCH_X86;
		for (String str : ABI) {
			if (archAsInt(str) == comparedArch) return true;
		}
		return false;
	}



	/**
	 * Convert an architecture from a String to an int.
	 * @param arch The architecture as a String
	 * @return The architecture as an int, can be UNSUPPORTED_ARCH if unknown.
	 */
	public static int archAsInt(String arch){
		if (arch == null) return UNSUPPORTED_ARCH;
		arch = arch.toLowerCase(java.util.Locale.ROOT).trim().replace(" ", "");
		if(arch.contains("arm64") || arch.equals("aarch64")) return ARCH_ARM64;
		if(arch.contains("arm") || arch.equals("aarch32")) return ARCH_ARM;
		if(arch.contains("x86_64") || arch.contains("amd64")) return ARCH_X86_64;
		if(arch.contains("x86") || (arch.startsWith("i") && arch.endsWith("86"))) return ARCH_X86;
		//Shouldn't happen
		return UNSUPPORTED_ARCH;
	}

	/**
	 * Convert to a string an architecture.
	 * @param arch The architecture as an int.
	 * @return "arm64" || "arm" || "x86_64" || "x86" || "UNSUPPORTED_ARCH"
	 */
	public static String archAsString(int arch){
		if(arch == ARCH_ARM64) return "arm64";
		if(arch == ARCH_ARM) return "arm";
		if(arch == ARCH_X86_64) return "x86_64";
		if(arch == ARCH_X86) return "x86";
		return "UNSUPPORTED_ARCH";
	}

}
