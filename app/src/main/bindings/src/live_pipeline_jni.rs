//! Single-call JNI fast-path for the live-camera planar OCR pipeline.
//!
//! Per-frame Kotlin calls `LivePipelineJni.processFrameGl(pipelinePtr,
//! framePtr, rendererPtr, displayXform, displayCrop, visibleSensorW/H,
//! fullViewW/H, tsNs)` on the GL render thread. We cast the
//! pointers back, run `pipeline.process_frame(...)` with a `PresentTarget`
//! that presents straight to the bound EGL surface, and return a packed
//! `jlong` carrying the tracker state + anchor id + inliers + ok flag so
//! Kotlin doesn't need a follow-up uniffi call for the debug-pill update.
//!
//! Detailed async-job telemetry (rec counts, ms, cancel) is *not*
//! packed into the per-frame return — Kotlin polls
//! `LivePlanarTracker.lastAcquireTelemetry()` for that whenever it
//! wants to refresh the debug pill.

#![cfg(feature = "planar-tracker")]

use std::sync::Arc;

use jni::objects::JClass;
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use translator::live_frame::LiveFrame;
use translator::live_tracker_pipeline::{LiveTrackerPipeline, PlanarTrackerState};
use translator::ocr::Rect as NativeRect;

/// Pack the per-frame result into a single `jlong`. Layout (bit 63 →
/// bit 0):
///
///   - 63..62 : state (Idle=0, Acquiring=1, Locked=2, Lost=3)
///   - 61     : composite_ok (1 if a frame was presented)
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

// ---------------------------------------------------------------------
// GPU present path. Runs the pipeline with a `PresentTarget` that renders
// the composite straight into the bound EGL surface via `GlesRenderer`.
// All three calls MUST run on the GL render thread Kotlin owns, with its
// EGL context current.
// ---------------------------------------------------------------------

#[cfg(feature = "gpu")]
use std::ffi::{c_char, c_void, CString};
#[cfg(feature = "gpu")]
use translator::gl_renderer::{GlesRenderer, PresentTarget};

#[cfg(feature = "gpu")]
#[link(name = "EGL")]
unsafe extern "C" {
    fn eglGetProcAddress(procname: *const c_char) -> *const c_void;
}

// dlopen/dlsym live in libc on Android; used to resolve core GLES2
// entry points that `eglGetProcAddress` may not (it only guarantees
// EGL/extension functions pre-API-18).
#[cfg(feature = "gpu")]
unsafe extern "C" {
    fn dlopen(filename: *const c_char, flag: i32) -> *mut c_void;
    fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
}

#[cfg(feature = "gpu")]
const RTLD_NOW: i32 = 2;

#[cfg(feature = "gpu")]
fn gl_proc(libgles: *mut c_void, name: &str) -> *const c_void {
    let Ok(c) = CString::new(name) else {
        return std::ptr::null();
    };
    if !libgles.is_null() {
        let p = unsafe { dlsym(libgles, c.as_ptr()) };
        if !p.is_null() {
            return p as *const c_void;
        }
    }
    unsafe { eglGetProcAddress(c.as_ptr()) }
}

/// Build a `GlesRenderer` on the calling thread. The caller (Kotlin's GL
/// render thread) must have created and made-current a GLES2 context on
/// the SurfaceView's surface first. Returns a raw `*mut GlesRenderer` as
/// a `jlong`, or 0 on failure; release it with [`destroyGlRenderer`] on
/// the same thread.
#[cfg(feature = "gpu")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_davidv_translator_LivePipelineJni_createGlRenderer(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    let libgles = {
        let name = CString::new("libGLESv2.so").unwrap();
        unsafe { dlopen(name.as_ptr(), RTLD_NOW) }
    };
    if libgles.is_null() {
        log::warn!("dlopen(libGLESv2.so) failed; relying on eglGetProcAddress only");
    }
    match GlesRenderer::new(|name| gl_proc(libgles, name)) {
        Ok(r) => Box::into_raw(Box::new(r)) as jlong,
        Err(e) => {
            log::error!("GlesRenderer::new failed: {e:?}");
            0
        }
    }
}

#[cfg(feature = "gpu")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_davidv_translator_LivePipelineJni_destroyGlRenderer(
    _env: JNIEnv,
    _class: JClass,
    renderer_ptr: jlong,
) {
    if renderer_ptr != 0 {
        // SAFETY: ptr came from `createGlRenderer`'s `Box::into_raw`,
        // dropped exactly once, on the GL thread that owns the context.
        unsafe { drop(Box::from_raw(renderer_ptr as *mut GlesRenderer)) };
    }
}

/// GPU sibling of [`processFrame`]: runs the tracker step and presents
/// the composite into the currently-bound framebuffer (the EGL window
/// surface). Kotlin sets the viewport and calls `eglSwapBuffers` after.
/// Returns the same packed result `jlong` (`composite_ok` reflects that a
/// frame was drawn).
#[cfg(feature = "gpu")]
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_dev_davidv_translator_LivePipelineJni_processFrameGl(
    env: JNIEnv,
    _class: JClass,
    pipeline_ptr: jlong,
    frame_ptr: jlong,
    renderer_ptr: jlong,
    display_xform: jni::objects::JFloatArray,
    display_crop_left: jint,
    display_crop_top: jint,
    display_crop_right: jint,
    display_crop_bottom: jint,
    visible_sensor_w: jint,
    visible_sensor_h: jint,
    full_view_w: jint,
    full_view_h: jint,
    timestamp_ns: jlong,
) -> jlong {
    if pipeline_ptr == 0 || frame_ptr == 0 || renderer_ptr == 0 {
        return 0;
    }
    if visible_sensor_w <= 0 || visible_sensor_h <= 0 {
        return 0;
    }
    // SAFETY: same contract as `processFrame` — Kotlin holds the wrapper
    // Arcs alive across the call; the renderer ptr came from
    // `createGlRenderer` and is used only on this (its owning) thread.
    let pipeline = unsafe { &*(pipeline_ptr as *const LiveTrackerPipeline) };
    let frame: Arc<LiveFrame> = unsafe {
        Arc::increment_strong_count(frame_ptr as *const LiveFrame);
        Arc::from_raw(frame_ptr as *const LiveFrame)
    };
    let renderer = unsafe { &mut *(renderer_ptr as *mut GlesRenderer) };

    // Row-major 3x3 dst-pixel → clip transform (surface size + display
    // rotation + FILL_CENTER scale), computed Kotlin-side per resize.
    let mut display_xform_buf = [0f32; 9];
    if env
        .get_float_array_region(&display_xform, 0, &mut display_xform_buf)
        .is_err()
    {
        log::warn!("processFrameGl: bad display_xform array");
        return 0;
    }

    let display_crop = NativeRect {
        left: display_crop_left.max(0) as u32,
        top: display_crop_top.max(0) as u32,
        right: display_crop_right.max(0) as u32,
        bottom: display_crop_bottom.max(0) as u32,
    };

    let mut target = PresentTarget {
        renderer,
        display_xform: display_xform_buf,
    };
    let result = pipeline.process_frame(
        &frame,
        display_crop,
        &mut target,
        visible_sensor_w as u32,
        visible_sensor_h as u32,
        visible_sensor_w as u32,
        visible_sensor_h as u32,
        full_view_w.max(0) as u32,
        full_view_h.max(0) as u32,
        timestamp_ns as u64,
    );

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
            log::warn!("process_frame_gl failed: {e:?}");
            0
        }
    }
}
