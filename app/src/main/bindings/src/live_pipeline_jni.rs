//! Single-call JNI fast-path for the live-camera planar OCR pipeline.
//!
//! Per-frame Kotlin calls `LivePipelineJni.processFrame(pipelinePtr,
//! framePtr, bitmap, displayCrop, visibleSensorW/H, fullViewW/H,
//! imuStable, tsNs)`. We:
//!
//!   1. Cast `pipelinePtr` back to `&LiveTrackerPipeline` and `framePtr`
//!      back to `&LiveFrame`.
//!   2. Lock the destination `Bitmap`'s pixel memory via
//!      `AndroidBitmap_lockPixels` to get a `&mut [u8]`.
//!   3. Call `pipeline.process_frame(...)` which runs the tracker
//!      step, composites directly into our slice, and (when needed)
//!      materializes frame bytes + dispatches an async acquire/refresh
//!      worker job.
//!   4. Unlock the bitmap and return a packed `jlong` carrying the
//!      tracker state + anchor id + inliers + composite-ok flag so
//!      Kotlin doesn't have to make a follow-up uniffi call for the
//!      common per-frame debug-pill update.
//!
//! Detailed async-job telemetry (rec counts, ms, cancel) is *not*
//! packed into the per-frame return — Kotlin polls
//! `LivePlanarTracker.lastAcquireTelemetry()` for that whenever it
//! wants to refresh the debug pill.

#![cfg(feature = "planar-tracker")]

use std::sync::Arc;

use jni::objects::{JClass, JObject};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;

use translator::live_frame::LiveFrame;
use translator::live_tracker_pipeline::{LiveTrackerPipeline, PlanarTrackerState};
use translator::ocr::Rect as NativeRect;

use crate::uniffi_catalog::PER_FRAME_TIMING_LOG;

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

const ANDROID_BITMAP_FORMAT_RGBA_8888: i32 = 1;

/// Pack the per-frame result into a single `jlong`. Layout (bit 63 →
/// bit 0):
///
///   - 63..62 : state (Idle=0, Acquiring=1, Locked=2, Lost=3)
///   - 61     : composite_ok (1 if the bitmap was written)
///   - 60..45 : inliers (16 bits, saturating)
///   - 44..29 : anchor_id low 16 bits — sequential, so the bottom bits
///              are stable across acquires until the engine wraps
///              (would take ~65 k acquires; debug pill loses uniqueness
///              past that but never blocks behavior)
///   - 28     : started_acquire
///   - 27     : started_refresh
///   - 26..0  : reserved (0)
fn pack_result(
    state: PlanarTrackerState,
    anchor_id: u64,
    inliers: u32,
    composite_ok: bool,
    started_acquire: bool,
    started_refresh: bool,
) -> jlong {
    let state_bits: u64 = match state {
        PlanarTrackerState::Idle => 0,
        PlanarTrackerState::Acquiring => 1,
        PlanarTrackerState::Locked => 2,
        PlanarTrackerState::Lost => 3,
    };
    let inliers_capped = inliers.min(0xFFFF) as u64;
    let anchor_lo = (anchor_id & 0xFFFF) as u64;
    let packed: u64 = (state_bits << 62)
        | (if composite_ok { 1u64 << 61 } else { 0 })
        | (inliers_capped << 45)
        | (anchor_lo << 29)
        | (if started_acquire { 1u64 << 28 } else { 0 })
        | (if started_refresh { 1u64 << 27 } else { 0 });
    packed as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_davidv_translator_LivePipelineJni_processFrame(
    mut env: JNIEnv,
    _class: JClass,
    pipeline_ptr: jlong,
    frame_ptr: jlong,
    bitmap: JObject,
    display_crop_left: jint,
    display_crop_top: jint,
    display_crop_right: jint,
    display_crop_bottom: jint,
    visible_sensor_w: jint,
    visible_sensor_h: jint,
    full_view_w: jint,
    full_view_h: jint,
    imu_stable: jboolean,
    timestamp_ns: jlong,
) -> jlong {
    if pipeline_ptr == 0 || frame_ptr == 0 {
        return 0;
    }
    // SAFETY: Kotlin holds the `Arc<LivePlanarTracker>` and
    // `Arc<FrameHandle>` wrappers for the duration of this call. The
    // raw addresses came from their `raw_address_for_jni` methods and
    // are valid + properly aligned.
    let pipeline = unsafe { &*(pipeline_ptr as *const LiveTrackerPipeline) };
    // Reconstruct an Arc<LiveFrame> for the pipeline call without
    // taking ownership of the caller's Arc: bump the strong count
    // and rebuild an Arc from the raw pointer. The original
    // (Kotlin-held) Arc remains valid; refcount is +1 while our local
    // Arc lives, then drops back when this function returns.
    let frame: Arc<LiveFrame> = unsafe {
        Arc::increment_strong_count(frame_ptr as *const LiveFrame);
        Arc::from_raw(frame_ptr as *const LiveFrame)
    };

    if visible_sensor_w <= 0 || visible_sensor_h <= 0 {
        return 0;
    }
    let bitmap_w = visible_sensor_w as u32;
    let bitmap_h = visible_sensor_h as u32;
    let needed = (bitmap_w as usize) * (bitmap_h as usize) * 4;

    let env_raw = env.get_raw();
    let bitmap_raw = bitmap.as_raw();
    if bitmap_raw.is_null() {
        return 0;
    }

    // Validate bitmap dims + format.
    let mut info = AndroidBitmapInfo::default();
    let rc = unsafe { AndroidBitmap_getInfo(env_raw, bitmap_raw, &mut info) };
    if rc != 0 {
        log::warn!("AndroidBitmap_getInfo failed: {rc}");
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
    if info.width != bitmap_w || info.height != bitmap_h {
        log::warn!(
            "Bitmap dims {}x{} != requested {}x{}",
            info.width,
            info.height,
            bitmap_w,
            bitmap_h,
        );
        return 0;
    }
    if info.stride != bitmap_w * 4 {
        log::warn!("Bitmap stride {} != width*4 {}", info.stride, bitmap_w * 4);
        return 0;
    }

    let mut pixels: *mut std::ffi::c_void = std::ptr::null_mut();
    let lock_rc = unsafe { AndroidBitmap_lockPixels(env_raw, bitmap_raw, &mut pixels) };
    if lock_rc != 0 || pixels.is_null() {
        log::warn!("AndroidBitmap_lockPixels failed: {lock_rc}");
        return 0;
    }
    // SAFETY: lockPixels gives us exclusive write access to `needed`
    // bytes until unlockPixels. Stride is tight (checked above) so
    // the layout matches what the compositor writes.
    let dst_slice = unsafe { std::slice::from_raw_parts_mut(pixels as *mut u8, needed) };

    let display_crop = NativeRect {
        left: display_crop_left.max(0) as u32,
        top: display_crop_top.max(0) as u32,
        right: display_crop_right.max(0) as u32,
        bottom: display_crop_bottom.max(0) as u32,
    };

    let t_proc = std::time::Instant::now();
    let result = pipeline.process_frame(
        &frame,
        display_crop,
        dst_slice,
        bitmap_w,
        bitmap_h,
        visible_sensor_w as u32,
        visible_sensor_h as u32,
        full_view_w.max(0) as u32,
        full_view_h.max(0) as u32,
        imu_stable != 0,
        timestamp_ns as u64,
    );
    if PER_FRAME_TIMING_LOG {
        log::info!(
            target: "planar_timing",
            "outer: process={:.1}ms dims={}x{}",
            t_proc.elapsed().as_secs_f64() * 1000.0,
            bitmap_w,
            bitmap_h,
        );
    }

    let unlock_rc = unsafe { AndroidBitmap_unlockPixels(env_raw, bitmap_raw) };
    if unlock_rc != 0 {
        log::warn!("AndroidBitmap_unlockPixels failed: {unlock_rc}");
    }
    let _ = &mut env;

    match result {
        Ok(r) => pack_result(
            r.state,
            r.anchor_id,
            r.inliers,
            r.composite_bytes > 0,
            r.started_acquire,
            r.started_refresh,
        ),
        Err(e) => {
            log::warn!("process_frame failed: {e:?}");
            0
        }
    }
}
