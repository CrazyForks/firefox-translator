//! JNI fast-path for the planar tracker's composited display frame.
//!
//! Companion to `LivePlanarTracker::process_and_composite` (uniffi).
//! The uniffi side runs the per-frame tracker step and stashes
//! `(active_anchor_id, h_surface_to_viewport)` on the tracker; this
//! shim then runs the actual camera-blit + overlay-warp composite
//! **directly into the Bitmap's pixel memory** via the NDK
//! `AndroidBitmap_lockPixels` API. Skips the previous chain of
//! Rust `Vec<u8>` → JNI memcpy → DirectByteBuffer →
//! `Bitmap.copyPixelsFromBuffer`: now it's just one write pass into
//! the bitmap's backing storage.
//!
//! Safety boundary: Kotlin must hold the `Arc<LivePlanarTracker>` and
//! the `Arc<FrameHandle>` for the duration of this call, otherwise
//! `tracker_ptr` / `frame_ptr` are dangling. Both wrappers are
//! `AutoCloseable` and live for the entire camera session. The
//! bitmap must be ARGB_8888 with `width * height * 4` bytes and
//! stride == width × 4 (the createBitmap defaults).

#![cfg(feature = "planar-tracker")]

use jni::objects::{JClass, JObject};
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use crate::uniffi_catalog::{FrameHandle, LivePlanarTracker, PER_FRAME_TIMING_LOG};

/// NDK `jnigraphics` bindings for Android Bitmap pixel-buffer access.
/// We link against `libjnigraphics.so` (system library on every
/// Android device) — no extra runtime dep. Functions return 0 on
/// success and negative `ANDROID_BITMAP_RESULT_*` codes on failure;
/// we treat any nonzero as a generic failure and bail.
#[link(name = "jnigraphics")]
unsafe extern "C" {
    fn AndroidBitmap_getInfo(
        env: *mut jni::sys::JNIEnv,
        jbitmap: jni::sys::jobject,
        info: *mut AndroidBitmapInfo,
    ) -> i32;
    fn AndroidBitmap_lockPixels(
        env: *mut jni::sys::JNIEnv,
        jbitmap: jni::sys::jobject,
        addr: *mut *mut std::ffi::c_void,
    ) -> i32;
    fn AndroidBitmap_unlockPixels(
        env: *mut jni::sys::JNIEnv,
        jbitmap: jni::sys::jobject,
    ) -> i32;
}

#[repr(C)]
#[derive(Default)]
struct AndroidBitmapInfo {
    width: u32,
    height: u32,
    stride: u32,
    format: i32,
    flags: u32,
}

/// `ANDROID_BITMAP_FORMAT_RGBA_8888` from `<android/bitmap.h>`.
/// Android `Bitmap.Config.ARGB_8888` maps to this format in NDK
/// (Android documents the bytes in memory as R, G, B, A — same byte
/// order as our composite_frame_into output).
const ANDROID_BITMAP_FORMAT_RGBA_8888: i32 = 1;

/// Composite the per-frame display image directly into the supplied
/// `Bitmap`'s pixel memory. Returns the number of bytes written
/// (0 on any failure — bad pointer, wrong format, dim mismatch,
/// composite math error).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_davidv_translator_PlanarRenderJni_compositeInto(
    mut env: JNIEnv,
    _class: JClass,
    tracker_ptr: jlong,
    frame_ptr: jlong,
    bitmap: JObject,
    sensor_width: jint,
    sensor_height: jint,
) -> jint {
    if tracker_ptr == 0 || frame_ptr == 0 {
        return 0;
    }
    let tracker = unsafe { &*(tracker_ptr as *const LivePlanarTracker) };
    let frame = unsafe { &*(frame_ptr as *const FrameHandle) };
    if sensor_width <= 0 || sensor_height <= 0 {
        return 0;
    }
    let sw = sensor_width as u32;
    let sh = sensor_height as u32;
    let needed = (sw as usize) * (sh as usize) * 4;

    let env_raw = env.get_raw();
    let bitmap_raw = bitmap.as_raw();
    if bitmap_raw.is_null() {
        return 0;
    }

    // Validate bitmap dims + format match what we're about to write.
    let mut info = AndroidBitmapInfo::default();
    let rc = unsafe { AndroidBitmap_getInfo(env_raw, bitmap_raw, &mut info) };
    if rc != 0 {
        log::warn!("AndroidBitmap_getInfo failed: {}", rc);
        return 0;
    }
    if info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 {
        log::warn!(
            "Bitmap format {} != RGBA_8888 ({})",
            info.format,
            ANDROID_BITMAP_FORMAT_RGBA_8888,
        );
        return 0;
    }
    if info.width != sw || info.height != sh {
        log::warn!(
            "Bitmap dims {}x{} != requested {}x{}",
            info.width,
            info.height,
            sw,
            sh,
        );
        return 0;
    }
    if info.stride != sw * 4 {
        // Non-tight stride is rare for ARGB_8888 createBitmap on
        // modern Android but possible. We don't currently handle a
        // strided destination; bail rather than corrupt pixels.
        log::warn!("Bitmap stride {} != width*4 {}", info.stride, sw * 4);
        return 0;
    }

    let mut pixels: *mut std::ffi::c_void = std::ptr::null_mut();
    let lock_rc = unsafe { AndroidBitmap_lockPixels(env_raw, bitmap_raw, &mut pixels) };
    if lock_rc != 0 || pixels.is_null() {
        log::warn!("AndroidBitmap_lockPixels failed: {}", lock_rc);
        return 0;
    }
    // SAFETY: lockPixels gives us exclusive write access to `needed`
    // bytes of bitmap memory until unlockPixels. Stride is tight
    // (checked above) so the layout matches our composite output.
    let dst_slice = unsafe { std::slice::from_raw_parts_mut(pixels as *mut u8, needed) };

    let (anchor_id, h) = match tracker.take_pending_compose() {
        Some((a, h)) => (a, Some(h)),
        None => (0u64, None),
    };
    let t_compose = std::time::Instant::now();
    let result = tracker.composite_into_slice(frame, dst_slice, sw, sh, h, anchor_id);
    if PER_FRAME_TIMING_LOG {
        log::info!(
            target: "planar_timing",
            "outer: composite={:.1}ms overlay_active={} dims={}x{}",
            t_compose.elapsed().as_secs_f64() * 1000.0,
            h.is_some(),
            sw,
            sh,
        );
    }

    let unlock_rc = unsafe { AndroidBitmap_unlockPixels(env_raw, bitmap_raw) };
    if unlock_rc != 0 {
        log::warn!("AndroidBitmap_unlockPixels failed: {}", unlock_rc);
        // Pixels were modified; the Bitmap may be in an inconsistent
        // state. But there's nothing else to do — the next draw will
        // either show the new pixels or a partial state depending on
        // SurfaceFlinger's view of the bitmap.
    }
    // Suppress unused-import warning when we don't actually use `env`
    // for anything beyond the raw pointer.
    let _ = &mut env;

    match result {
        Ok(()) => needed as jint,
        Err(_) => 0,
    }
}
