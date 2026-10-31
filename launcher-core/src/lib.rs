//! Launcher-only I/O. No GL context, renderer, Android UI or game JVM dependency.
use jni::{JNIEnv, objects::{JClass, JString, JByteArray}, sys::jstring};
use sha2::{Digest, Sha256};
use std::{fs::{self, File, OpenOptions}, io::{self, Read, Write}, path::{Path, PathBuf},
          panic::{catch_unwind, AssertUnwindSafe}, sync::atomic::{AtomicU64, Ordering}};
use std::os::unix::fs::OpenOptionsExt;

fn hash_reader(mut reader: impl Read, mut cancelled: impl FnMut() -> io::Result<bool>) -> io::Result<String> {
    let mut digest = Sha256::new();
    let mut buffer = [0u8; 64 * 1024];
    loop {
        if cancelled()? { return Err(io::Error::new(io::ErrorKind::Interrupted, "Hashing interrupted")); }
        let count = match reader.read(&mut buffer) {
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            result => result?,
        };
        if count == 0 { break; }
        digest.update(&buffer[..count]);
    }
    Ok(format!("{:x}", digest.finalize()))
}

struct Temporary(PathBuf);
impl Drop for Temporary { fn drop(&mut self) { let _ = fs::remove_file(&self.0); } }

/// Same-directory replacement: interruption before rename leaves the previous file intact.
/// File contents are synced; directory sync is best effort (some filesystems reject it).
fn atomic_write(path: &Path, bytes: &[u8]) -> io::Result<()> {
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let parent = path.parent().filter(|p| !p.as_os_str().is_empty()).unwrap_or(Path::new("."));
    fs::create_dir_all(parent)?;
    let stamp = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default().as_nanos();
    let mut reserved = None;
    for _ in 0..16 {
        let temp = parent.join(format!(".gurkha-{}-{stamp}-{}.tmp", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
        match OpenOptions::new().write(true).create_new(true).mode(0o600).open(&temp) {
            Ok(file) => { reserved = Some((Temporary(temp), file)); break; }
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => continue,
            Err(e) => return Err(e),
        }
    }
    let (temp, mut file) = reserved.ok_or_else(|| io::Error::new(io::ErrorKind::AlreadyExists, "Could not reserve temporary file"))?;
    file.write_all(bytes)?;
    file.sync_all()?;
    drop(file);
    fs::rename(&temp.0, path)?;
    if let Ok(dir) = File::open(parent) { let _ = dir.sync_all(); }
    Ok(())
}

fn io_error(error: impl std::fmt::Display) -> io::Error { io::Error::other(error.to_string()) }
fn report(env: &mut JNIEnv, error: io::Error) {
    if env.exception_check().unwrap_or(true) { return; } // Keep an existing JVM exception.
    let class = if error.kind() == io::ErrorKind::Interrupted { "java/io/InterruptedIOException" } else { "java/io/IOException" };
    let _ = env.throw_new(class, error.to_string());
}

#[no_mangle]
pub extern "system" fn Java_com_movtery_zalithlauncher_utils_file_RustCore_nativeSha256(
    mut env: JNIEnv, _class: JClass, path: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| -> io::Result<String> {
        let path: String = env.get_string(&path).map_err(io_error)?.into();
        let thread = env.call_static_method("java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", &[])
            .and_then(|v| v.l()).map_err(io_error)?;
        hash_reader(File::open(path)?, || {
            // Method lookup can create local JNI references. Pop them each chunk instead of
            // accumulating them for a multi-gigabyte mod/archive in one native invocation.
            env.with_local_frame(8, |env| env.call_method(&thread, "isInterrupted", "()Z", &[])
                .and_then(|v| v.z())).map_err(io_error)
        })
    }));
    match result {
        Ok(Ok(hash)) => match env.new_string(hash) { Ok(s) => s.into_raw(), Err(e) => { report(&mut env, io_error(e)); std::ptr::null_mut() } },
        Ok(Err(e)) => { report(&mut env, e); std::ptr::null_mut() },
        Err(_) => { report(&mut env, io_error("Rust hashing failed")); std::ptr::null_mut() },
    }
}

#[no_mangle]
pub extern "system" fn Java_com_movtery_zalithlauncher_utils_file_RustCore_nativeAtomicWrite(
    mut env: JNIEnv, _class: JClass, path: JString, bytes: JByteArray,
) {
    let result = catch_unwind(AssertUnwindSafe(|| -> io::Result<()> {
        let path: String = env.get_string(&path).map_err(io_error)?.into();
        let bytes = env.convert_byte_array(&bytes).map_err(io_error)?;
        atomic_write(Path::new(&path), &bytes)
    }));
    match result {
        Ok(Ok(())) => (),
        Ok(Err(e)) => report(&mut env, e),
        Err(_) => report(&mut env, io_error("Rust atomic write failed")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn sha256_vectors_and_cancellation() {
        assert_eq!(hash_reader(&b""[..], || Ok(false)).unwrap(), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assert_eq!(hash_reader(&b"abc"[..], || Ok(false)).unwrap(), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assert_eq!(hash_reader(&b"abc"[..], || Ok(true)).unwrap_err().kind(), io::ErrorKind::Interrupted);
    }
    #[test] fn reader_errors_are_not_successful_hashes() {
        struct Broken;
        impl Read for Broken { fn read(&mut self, _: &mut [u8]) -> io::Result<usize> { Err(io_error("test read failure")) } }
        assert!(hash_reader(Broken, || Ok(false)).is_err());
    }
}
