//! JNI fast-path for live-OCR frame ingestion.
//!
//! Kotlin's `LiveFrameJni.writeFrom(handlePtr, src, …)` and
//! `setExternalBuffer` land here. We reinterpret `handle_ptr` (produced
//! by [`FrameHandle::raw_address_for_jni`]) as
//! `&translator::live_frame::LiveFrame`, take the native address of the
//! source `DirectByteBuffer`, and call the safe(-ish) `LiveFrame`
//! wrappers — they encapsulate the unsafe pointer copy / borrow.
//!
//! Materialization of an external borrow + dropping it without copying
//! are *not* exposed as JNI methods anymore: the pipeline calls those
//! internally inside `process_frame`, deciding per-frame whether an
//! async job needs owned bytes or the borrow can be cleared.

use jni::JNIEnv;
use jni::objects::{JByteBuffer, JClass};
use jni::sys::{jboolean, jint, jlong};

use translator::live_frame::LiveFrame;

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
    // SAFETY: Kotlin holds the `Arc<LiveFrame>` wrapper for the
    // duration of this call. The pointer originated from
    // `LiveFrame::raw_address`, is properly aligned, and points at
    // valid memory.
    let frame = unsafe { &*(handle_ptr as *const LiveFrame) };

    let src_addr = match env.get_direct_buffer_address(&src) {
        Ok(p) if !p.is_null() => p,
        _ => return jni::sys::JNI_FALSE,
    };
    let src_capacity = env.get_direct_buffer_capacity(&src).unwrap_or(0);
    if (length as usize) > src_capacity {
        return jni::sys::JNI_FALSE;
    }
    // SAFETY: capacity check above guarantees `src_addr` is valid for
    // `length` bytes; DirectByteBuffer memory outlives this call.
    unsafe {
        frame.write_from_raw(
            src_addr,
            length as usize,
            width as u32,
            height as u32,
            rotation,
        );
    }
    jni::sys::JNI_TRUE
}

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
    let frame = unsafe { &*(handle_ptr as *const LiveFrame) };
    let src_addr = match env.get_direct_buffer_address(&src) {
        Ok(p) if !p.is_null() => p,
        _ => return jni::sys::JNI_FALSE,
    };
    let src_capacity = env.get_direct_buffer_capacity(&src).unwrap_or(0);
    if (length as usize) > src_capacity {
        return jni::sys::JNI_FALSE;
    }
    // SAFETY: Kotlin keeps the source `ImageProxy` alive across the
    // upcoming `processFrame` JNI call (the only consumer of this
    // borrow). The pipeline either materializes an owned copy
    // (acquire/refresh) or clears the borrow (pure tracking frame)
    // synchronously before returning, and the proxy is closed only
    // after `processFrame` returns.
    unsafe {
        frame.set_external_buffer(
            src_addr,
            length as usize,
            width as u32,
            height as u32,
            rotation,
        );
    }
    jni::sys::JNI_TRUE
}
