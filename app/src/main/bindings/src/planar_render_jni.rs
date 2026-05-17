//! JNI fast-path for the planar tracker's overlay bitmap.
//!
//! Companion to `prepare_text_overlay_render` (uniffi method on
//! `LivePlanarTracker`). The uniffi side runs the rasterizer and parks
//! the RGBA bytes in `LivePlanarTracker::pending_bitmap`; this shim
//! takes them out and memcpys them into a Kotlin-owned
//! `DirectByteBuffer`. The buffer's backing memory belongs to the
//! caller (preallocated for the canonical-frame size), so there's no
//! allocation per render — and crucially, no uniffi `Vec<u8>`
//! marshalling + JVM `ByteArray` round-trip on the multi-MB return.
//!
//! Safety boundary: Kotlin must hold the `Arc<LivePlanarTracker>` for
//! the duration of this call, otherwise `tracker_ptr` is dangling. The
//! uniffi wrapper class is `AutoCloseable`; the planar-tracker engine
//! lives for the entire camera session.

#![cfg(feature = "planar-tracker")]

use jni::objects::{JByteBuffer, JClass};
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use crate::uniffi_catalog::LivePlanarTracker;

/// Pop the pending bitmap from `tracker_ptr` and memcpy it into `dst`
/// (a `DirectByteBuffer` with capacity ≥ length of the pending bytes).
/// Returns the number of bytes written, or 0 on any failure (no pending
/// bitmap, null buffer, insufficient capacity, poisoned mutex).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_davidv_translator_PlanarRenderJni_renderInto(
    env: JNIEnv,
    _class: JClass,
    tracker_ptr: jlong,
    dst: JByteBuffer,
) -> jint {
    if tracker_ptr == 0 {
        return 0;
    }
    // SAFETY: Kotlin holds the wrapper class while this call is in
    // flight; the address came from `raw_address_for_jni` and is
    // properly aligned.
    let tracker = unsafe { &*(tracker_ptr as *const LivePlanarTracker) };

    let bytes = match tracker.take_pending_bitmap() {
        Some(b) => b,
        None => return 0,
    };

    copy_into_direct_buffer(env, &dst, &bytes)
}

/// Pop the pending composited display frame and memcpy it into `dst`.
/// Pair with `LivePlanarTracker.composite_frame` (uniffi). Same
/// rationale as `renderInto`: skip uniffi's `Vec<u8>` marshalling cost
/// for an 8 MB-class buffer per camera frame.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_davidv_translator_PlanarRenderJni_compositeInto(
    env: JNIEnv,
    _class: JClass,
    tracker_ptr: jlong,
    dst: JByteBuffer,
) -> jint {
    if tracker_ptr == 0 {
        return 0;
    }
    let tracker = unsafe { &*(tracker_ptr as *const LivePlanarTracker) };
    let bytes = match tracker.take_pending_display() {
        Some(b) => b,
        None => return 0,
    };
    copy_into_direct_buffer(env, &dst, &bytes)
}

fn copy_into_direct_buffer(env: JNIEnv, dst: &JByteBuffer, bytes: &[u8]) -> jint {
    let dst_addr = match env.get_direct_buffer_address(dst) {
        Ok(p) if !p.is_null() => p,
        _ => return 0,
    };
    let dst_capacity = env.get_direct_buffer_capacity(dst).unwrap_or(0);
    if bytes.len() > dst_capacity {
        return 0;
    }
    // SAFETY: dst_addr points to a valid DirectByteBuffer region of at
    // least `bytes.len()` bytes (we checked capacity). Source and dest
    // are disjoint — `bytes` is Rust-owned, `dst` is JVM-owned native
    // memory.
    unsafe {
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), dst_addr, bytes.len());
    }
    bytes.len() as jint
}
