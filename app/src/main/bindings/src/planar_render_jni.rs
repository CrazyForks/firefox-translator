//! JNI fast-path for the planar tracker's composited display frame.
//!
//! Companion to `LivePlanarTracker::process_and_composite` (uniffi).
//! The uniffi side runs the per-frame tracker step and stashes
//! `(active_anchor_id, h_surface_to_viewport)` on the tracker; this
//! shim then runs the actual rotate+overlay composite **directly
//! into** a Kotlin-owned `DirectByteBuffer`. Eliminates the previous
//! `pending_display: Vec<u8>` intermediate and the JNI memcpy that
//! copied it into the buffer afterwards — saves ~1.5 ms of JNI
//! memcpy + a 4.9 MB Vec allocation per frame.
//!
//! Safety boundary: Kotlin must hold the `Arc<LivePlanarTracker>` and
//! the `Arc<FrameHandle>` for the duration of this call, otherwise
//! `tracker_ptr` / `frame_ptr` are dangling. Both wrappers are
//! `AutoCloseable` and live for the entire camera session.

#![cfg(feature = "planar-tracker")]

use jni::objects::{JByteBuffer, JClass};
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use crate::uniffi_catalog::{FrameHandle, LivePlanarTracker, PER_FRAME_TIMING_LOG};

/// Composite the per-frame display image (camera rotated to display
/// orientation + overlay quads warped on top) directly into `dst`.
/// Pair with `LivePlanarTracker.process_and_composite` (uniffi): that
/// call runs the tracker step and stashes the H + anchor id; this
/// call consumes them and writes the actual pixels to `dst`. Returns
/// the number of bytes written (0 on any failure — bad pointer, bad
/// buffer capacity, composite math error).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_davidv_translator_PlanarRenderJni_compositeInto(
    env: JNIEnv,
    _class: JClass,
    tracker_ptr: jlong,
    frame_ptr: jlong,
    dst: JByteBuffer,
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
    let dst_addr = match env.get_direct_buffer_address(&dst) {
        Ok(p) if !p.is_null() => p,
        _ => return 0,
    };
    let dst_capacity = env.get_direct_buffer_capacity(&dst).unwrap_or(0);
    if needed > dst_capacity {
        return 0;
    }
    // SAFETY: dst_addr points to a valid DirectByteBuffer region of at
    // least `needed` bytes (checked above). We hold exclusive access
    // for the duration of this call — Kotlin is on the detector
    // worker thread and won't touch the buffer until we return. The
    // tracker + frame mutex acquires happen inside
    // `composite_into_slice`. Output is sensor-orient; SurfaceView
    // rotates for display.
    let dst_slice = unsafe { std::slice::from_raw_parts_mut(dst_addr, needed) };

    let (anchor_id, h) = match tracker.take_pending_compose() {
        Some((a, h)) => (a, Some(h)),
        None => (0u64, None),
    };
    let t_compose = std::time::Instant::now();
    let result = tracker.composite_into_slice(frame, dst_slice, h, anchor_id);
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
    match result {
        Ok(()) => needed as jint,
        Err(_) => 0,
    }
}
