//! JNI fast-path for live-OCR frame ingestion.
//!
//! Kotlin's `LiveFrameJni.writeFrom(handlePtr, srcBuffer, …)` lands here. We
//! reinterpret the `handle_ptr` (sent over as a `jlong`, originally produced by
//! [`FrameHandle::raw_address_for_jni`]) as `&FrameHandle`, take the native
//! address of the source `DirectByteBuffer`, and `memcpy` straight into the
//! handle's `Vec<u8>`. No JVM ByteArray allocation, no uniffi marshalling copy.
//!
//! Safety boundary: Kotlin must hold the `Arc<FrameHandle>` wrapper for the
//! duration of this call, otherwise the pointer is dangling. The wrapper class
//! is `AutoCloseable`/`Disposable`; the engine's handle pool keeps it alive
//! from acquire to release.

use jni::JNIEnv;
use jni::objects::{JByteBuffer, JClass};
use jni::sys::{jboolean, jint, jlong};

use crate::uniffi_catalog::FrameHandle;

/// Copy `length` bytes from a contiguous DirectByteBuffer into the FrameHandle's
/// underlying RGBA vector. Updates dimensions + rotation atomically and clears
/// the cached oriented image. Returns JNI_TRUE on success, JNI_FALSE on any
/// failure (null buffer, invalid handle, lock poisoned, mismatched length).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_davidv_translator_LiveFrameJni_writeFrom(
    env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
    src: JByteBuffer,
    length: jint,
    width: jint,
    height: jint,
    rotation: jint,
) -> jboolean {
    if handle_ptr == 0 || length <= 0 {
        return jni::sys::JNI_FALSE;
    }
    // SAFETY: the caller (Kotlin) holds an Arc<FrameHandle>, keeping the address
    // valid for the duration of this call. The pointer originally came from
    // FrameHandle::raw_address_for_jni and is properly aligned.
    let handle = unsafe { &*(handle_ptr as *const FrameHandle) };

    let src_addr = match env.get_direct_buffer_address(&src) {
        Ok(p) if !p.is_null() => p,
        _ => return jni::sys::JNI_FALSE,
    };
    let src_capacity = env.get_direct_buffer_capacity(&src).unwrap_or(0);
    if (length as usize) > src_capacity {
        return jni::sys::JNI_FALSE;
    }

    let mut state = match handle.state().lock() {
        Ok(s) => s,
        Err(_) => return jni::sys::JNI_FALSE,
    };

    state.rgba.clear();
    state.rgba.reserve(length as usize);
    // SAFETY: `src_addr` points to a valid DirectByteBuffer region of at least
    // `length` bytes (we checked capacity above). The dest buffer was just
    // reserved with at least `length` extra capacity. The two regions are
    // disjoint — `src` is JVM-owned native memory, `dst` is the Rust Vec's
    // backing allocation.
    unsafe {
        std::ptr::copy_nonoverlapping(src_addr, state.rgba.as_mut_ptr(), length as usize);
        state.rgba.set_len(length as usize);
    }
    // Clear any leftover external borrow: this path is the legacy
    // memcpy ingestion (used by the stride-padded fallback in
    // LiveCameraScreen); the owned `rgba` is now authoritative.
    state.external_rgba = None;
    state.width = width as u32;
    state.height = height as u32;
    state.rotation_degrees = rotation;
    state.cached = None;
    jni::sys::JNI_TRUE
}

/// Zero-copy ingestion: borrow the camera ImageProxy's
/// DirectByteBuffer into the FrameHandle without memcpying. The
/// caller (Kotlin) MUST keep the backing ImageProxy alive until
/// either [`Java_..._materializeOwned`] is invoked OR the FrameHandle
/// is reset via another writeFrom / setExternalBuffer / clear call.
/// Returns JNI_TRUE on success, JNI_FALSE on failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_davidv_translator_LiveFrameJni_setExternalBuffer(
    env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
    src: JByteBuffer,
    length: jint,
    width: jint,
    height: jint,
    rotation: jint,
) -> jboolean {
    if handle_ptr == 0 || length <= 0 {
        return jni::sys::JNI_FALSE;
    }
    // SAFETY: same justification as writeFrom — Kotlin holds the Arc.
    let handle = unsafe { &*(handle_ptr as *const FrameHandle) };
    let src_addr = match env.get_direct_buffer_address(&src) {
        Ok(p) if !p.is_null() => p,
        _ => return jni::sys::JNI_FALSE,
    };
    let src_capacity = env.get_direct_buffer_capacity(&src).unwrap_or(0);
    if (length as usize) > src_capacity {
        return jni::sys::JNI_FALSE;
    }
    let mut state = match handle.state().lock() {
        Ok(s) => s,
        Err(_) => return jni::sys::JNI_FALSE,
    };
    state.external_rgba = Some(crate::uniffi_catalog::ExternalRgba {
        ptr: src_addr,
        len: length as usize,
    });
    state.width = width as u32;
    state.height = height as u32;
    state.rotation_degrees = rotation;
    state.cached = None;
    jni::sys::JNI_TRUE
}

/// Copy the currently-borrowed external buffer into the FrameHandle's
/// owned `rgba` Vec, then release the borrow. Call this just before
/// `proxy.close()` when an acquire / refresh pipeline needs to keep
/// reading the camera bytes after the per-frame fast path has
/// returned — the async pipeline must own its data because the
/// ImageProxy will go away. No-op when no external buffer is set.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_davidv_translator_LiveFrameJni_materializeOwned(
    _env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
) {
    if handle_ptr == 0 {
        return;
    }
    let handle = unsafe { &*(handle_ptr as *const FrameHandle) };
    if let Ok(mut state) = handle.state().lock() {
        state.materialize_owned();
    }
}

/// Clear the external buffer borrow without copying. Use when the
/// frame is being dropped (e.g. the analyzer overwrote `pendingFrame`
/// before the worker drained it) and we just want to invalidate the
/// pointer before the ImageProxy closes.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_davidv_translator_LiveFrameJni_clearExternalBuffer(
    _env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
) {
    if handle_ptr == 0 {
        return;
    }
    let handle = unsafe { &*(handle_ptr as *const FrameHandle) };
    if let Ok(mut state) = handle.state().lock() {
        state.external_rgba = None;
        // Don't touch `state.rgba` here — it may still hold valid
        // data from a previous writeFrom or materialize that
        // something else is reading.
    }
}
