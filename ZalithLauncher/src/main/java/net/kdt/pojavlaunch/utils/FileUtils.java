package net.kdt.pojavlaunch.utils;

import java.io.File;
import java.io.IOException;

public class FileUtils {
    /**
     * Check if a file denoted by a String path exists.
     * @param filePath the path to check
     * @return whether it exists (same as File.exists()
     */
    public static boolean exists(String filePath){
        return new File(filePath).exists();
    }

    /**
     * Get the file name from a path/URL string.
     * @param pathOrUrl the path or the URL of the file
     * @return the file's name
     */
    public static String getFileName(String pathOrUrl) {
        int lastSlashIndex = pathOrUrl.lastIndexOf('/');
        if(lastSlashIndex == -1) return null;
        return pathOrUrl.substring(lastSlashIndex);
    }

    /**
     * Remove the extension (all text after the last dot) from a path/URL string.
     * @param pathOrUrl the path or the URL of the file
     * @return the input with the extension removed
     */
    public static String removeExtension(String pathOrUrl) {
        int lastDotIndex = pathOrUrl.lastIndexOf('.');
        if(lastDotIndex == -1) return pathOrUrl;
        return pathOrUrl.substring(0, lastDotIndex);
    }

    /**
     * Ensure that a directory exists, is a directory and is writable.
     * @param targetFile the directory to check
     * @return if the check has succeeded
     */
    public static boolean ensureDirectorySilently(File targetFile) {
        if(targetFile.isFile()) return false;
        if(targetFile.exists()) return targetFile.canWrite();
        else return targetFile.mkdirs();

    }

    /**
     * Ensure that the parent directory of a file exists and is writable
     * @param targetFile the File whose parent should be checked
     * @return if the check as succeeded
     */
    public static boolean ensureParentDirectorySilently(File targetFile) {
        File parentFile = targetFile.getParentFile();
        if(parentFile == null) return false;
        return ensureDirectorySilently(parentFile);
    }

    /**
     * Same as ensureDirectorySilently(), but throws an IOException telling why the check failed.
     * @param targetFile the directory to check
     * @throws IOException when the checks fail
     */
    public static void ensureDirectory(File targetFile) throws IOException{
        if(targetFile.isFile()) throw new IOException("Target directory is a file");
        if(targetFile.exists()) {
            if(!targetFile.canWrite()) throw new IOException("Target directory is not writable");
        }else if(!targetFile.mkdirs()) throw new IOException("Unable to create target directory");
    }

    /**
     * Same as ensureParentDirectorySilently(), but throws an IOException telling why the check failed.
     * @param targetFile the File whose parent should be checked
     * @throws IOException when the checks fail
     */
    public static void ensureParentDirectory(File targetFile) throws IOException{
        File parentFile = targetFile.getParentFile();
        if(parentFile == null) throw new IOException("targetFile does not have a parent");
        ensureDirectory(parentFile);
    }

    /**
     * Resolve an untrusted relative path (a zip/tar entry name, a path from a modpack index...)
     * against a destination directory, and make sure the result stays inside that directory.
     * This blocks "zip slip" style attacks, where an entry such as "../../files/x" or a symlinked
     * parent directory would make us write files outside of the destination.
     * @param parent the directory that must contain the result
     * @param relativePath the untrusted path to resolve
     * @return the resolved file (it may be the parent directory itself, e.g. for a "./" entry)
     * @throws IOException if the resolved path is outside of the parent directory
     */
    public static File resolveSafeChild(File parent, String relativePath) throws IOException {
        return resolveSafeChild(parent.getCanonicalPath(), parent, relativePath);
    }

    /**
     * Same as {@link #resolveSafeChild(File, String)}, but reuses an already computed canonical
     * path of the parent directory (useful when extracting many entries into the same directory).
     */
    public static File resolveSafeChild(String canonicalParent, File parent, String relativePath) throws IOException {
        if (relativePath == null) throw new IOException("Missing path");
        File child = new File(parent, relativePath);
        String canonicalChild = child.getCanonicalPath();
        if (!canonicalChild.equals(canonicalParent)
                && !canonicalChild.startsWith(canonicalParent.endsWith(File.separator) ? canonicalParent : canonicalParent + File.separator)) {
            throw new IOException("Blocked unsafe path \"" + relativePath + "\": it points outside of " + canonicalParent);
        }
        return child;
    }
}