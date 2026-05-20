/*
 * Copyright (C) 2024 David V
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.davidv.translator

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.bindings.FrameHandle
import uniffi.bindings.PlanarTrackerState
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import uniffi.translator.Rect as NativeRect

private const val FRAME_BUFFER_CAPACITY_BYTES_PLANAR: Int = 8 * 1024 * 1024
private const val MAX_RETAINED_FRAME_HANDLES_PLANAR: Int = 4
private const val TAG_PLANAR = "LivePlanarOcrEngine"

/** Full-frame detection: the planar tracker already restricts anchor
 *  features to the union of detected text bboxes, so we no longer need
 *  the old per-region tracker's center crop to keep work bounded. */
private const val CENTER_CROP_FRACTION_PLANAR = 1.0f

/** Compute the algorithm's `display_crop` from the SurfaceView's
 *  FILL_CENTER visible region. Returns the full display frame when
 *  view dims aren't available yet (surface not laid out, viewW/H = 0),
 *  so first-frame behaviour matches the previous full-sensor path
 *  rather than failing.
 *
 *  Math mirrors `LiveTranslatorSurfaceView.drawComposited`:
 *  scale = max(viewW/displayW, viewH/displayH) (FILL_CENTER); the
 *  visible portion of the bitmap is viewW/scale × viewH/scale,
 *  centred. Result is in display-orient coords. */
private fun computeDisplayCrop(
  sensorW: Int,
  sensorH: Int,
  rotationDegrees: Int,
  viewW: Int,
  viewH: Int,
): NativeRect {
  val r = ((rotationDegrees % 360) + 360) % 360
  val displayW: Int
  val displayH: Int
  if (r == 90 || r == 270) {
    displayW = sensorH
    displayH = sensorW
  } else {
    displayW = sensorW
    displayH = sensorH
  }
  if (viewW <= 0 || viewH <= 0 || displayW <= 0 || displayH <= 0) {
    return NativeRect(0u, 0u, displayW.toUInt(), displayH.toUInt())
  }
  val scale =
    kotlin.math.max(
      viewW.toFloat() / displayW.toFloat(),
      viewH.toFloat() / displayH.toFloat(),
    )
  if (scale <= 0f) {
    return NativeRect(0u, 0u, displayW.toUInt(), displayH.toUInt())
  }
  val visibleDispW = (viewW.toFloat() / scale).coerceAtMost(displayW.toFloat())
  val visibleDispH = (viewH.toFloat() / scale).coerceAtMost(displayH.toFloat())
  val left = ((displayW - visibleDispW) * 0.5f).toInt().coerceAtLeast(0)
  val top = ((displayH - visibleDispH) * 0.5f).toInt().coerceAtLeast(0)
  val right = (left + visibleDispW.toInt()).coerceAtMost(displayW)
  val bottom = (top + visibleDispH.toInt()).coerceAtMost(displayH)
  return NativeRect(
    left.toUInt(),
    top.toUInt(),
    right.toUInt(),
    bottom.toUInt(),
  )
}

private const val DETECTOR_TARGET_PIXELS_PLANAR_UINT: UInt = 650_000u
private const val DETECTOR_TARGET_PIXELS_PLANAR_INT: Int = 650_000

/** Pad around each detected text bbox before building the anchor.
 *  Padding lets the tracker include a few corners just outside the text
 *  (e.g. the glyph stems' inverse pixels, page edges, borders) which
 *  helps homography fit when the text itself is sparse. */
private const val ANCHOR_REGION_PAD_PX_UINT: UInt = 60u

/** Frames of sustained LOST before we *hide* the overlay (vs just
 *  keep the last good one). 8 frames ≈ 270 ms at 30 fps — short enough
 *  to react when the user pans away, long enough to ignore single-
 *  frame blur or one bad match. */
private const val LOSS_HIDE_AFTER_FRAMES: Int = 4

/* Adaptive homography-smoothing thresholds (pixels). Below `LOW` we
 *  treat consecutive-frame differences as feature noise → heavy
 *  smoothing. Above `HIGH` we treat them as real camera/subject motion
 *  → snap immediately to the new pose. Linear blend in between. */

/** Lower bound on smoothing α. Higher = more responsive to motion,
 *  less suppression of static-scene jitter. 0.35 leaves ~half the
 *  static bounce visible but cuts lag during slow paper motion. */
private const val SMOOTH_LOW_PX: Float = 3f
private const val SMOOTH_HIGH_PX: Float = 9f
private const val SMOOTH_MIN_ALPHA: Float = 0.35f

/** Normalized-coord threshold to treat a focus-point change as an
 *  explicit "user re-aimed" event. 5% of the frame width/height is
 *  small enough to ignore tracking noise but big enough to catch a
 *  real tap-to-focus elsewhere on screen. */
private const val FOCUS_RESET_THRESHOLD: Float = 0.05f

/** Carries one camera frame through the engine. [proxy] is held
 *  alive for zero-copy ingestion — the Rust side borrows the camera
 *  DirectByteBuffer rather than memcpying. Closed by the worker after
 *  the per-frame fast path completes (and after any async pipeline
 *  has materialized an owned copy via [LiveFrameJni.materializeOwned]).
 *  Null only on the legacy memcpy fallback or the stride-padded path
 *  where the worker has no proxy to close. */
private data class PendingPlanarFrame(
  val handle: FrameHandle,
  val sensorWidth: Int,
  val sensorHeight: Int,
  val rotationDegrees: Int,
  val focusXNormalized: Float,
  val focusYNormalized: Float,
  val from: Language,
  val to: Language,
  val isAutoSource: Boolean,
  val imuRotationAtCapture: FloatArray?,
  /** Sensor exposure timestamp (CLOCK_BOOTTIME, same clock as
   *  `SensorEvent.timestamp`). */
  val captureTimestampNs: Long,
  val convertMs: Double,
  val proxy: androidx.camera.core.ImageProxy?,
  /** Live `SurfaceView` dimensions at submit time. Used to compute a
   *  `display_crop` matching the FILL_CENTER visible region so the
   *  algorithm processes only what the user sees — preserves the
   *  preview-as-framing-feedback contract. `0` means dims weren't
   *  available (surface not yet sized); fall back to full sensor. */
  val viewWidth: Int,
  val viewHeight: Int,
)

/* Per-anchor canonical entries: a recognised text region in canonical
 *  (full-crop) coordinates. Stays valid as long as the anchor lives in
 *  the LRU cache. */

/** Debug status snapshot for the on-screen tracker pill. Updated on
 *  every camera frame (planar tracker state) and on every rec batch
 *  (det/rec counts). Kept lightweight enough that a 30 Hz emit is
 *  cheap. Visible only when `DEBUG_SHOW_TRACKER_STATUS` is true. */
data class TrackerStatus(
  val state: uniffi.bindings.PlanarTrackerState,
  val anchorId: Long,
  /** Inliers from the most recent Locked frame. 0 otherwise. */
  val inliers: Int,
  /** From the most recent acquire stage. -1 = no acquire yet. */
  val lastAcquireDet: Int,
  val lastAcquireRecOk: Int,
  val lastAcquireRecEmpty: Int,
  val lastAcquirePending: Int,
)

/* Phase 1 bitmap-overlay payload (FUTURE_BITMAP_OVERLAY.md).
 *  The view layer warps `bitmap` via `Matrix.setPolyToPoly` from the
 *  bitmap's own corners (in canonical-frame coords) to the projected
 *  corners (current-frame coords). `cropLeft` / `cropTop` translate the
 *  result into the camera-preview display space. */

/** Detector contour debug overlay (cyan polygons over the camera
 *  preview). Currently never emitted by the planar engine — kept for
 *  future use and for `LiveCameraScreen`'s gated debug renderer. */
data class DebugContourFrame(
  val contoursDisplay: List<FloatArray>,
  val frameWidth: Int,
  val frameHeight: Int,
)

/** Pinhole intrinsics in the same pixel space as the analyser frame.
 *  Plumbed from the camera info through the engine to the view so
 *  Phase-5 render-thread IMU extrapolation can predict per-pixel
 *  motion at refresh rate. */
data class FrameIntrinsics(val fx: Float, val fy: Float, val cx: Float, val cy: Float)

/** One per-camera-frame composited display image: camera pixels in
 *  display orientation with the current overlay warped + alpha-blended
 *  on top. Produced by Rust's `composite_frame` and delivered via JNI
 *  memcpy into [bitmap]. The view blits this 1:1 (with FILL_CENTER
 *  letterboxing) to a SurfaceView — no further compositing on the
 *  Kotlin side. Replaces the old [BitmapOverlayFrame] split, which
 *  drew the camera and overlay on separate surfaces and could drift
 *  apart under motion. */
data class CompositedFrame(
  val bitmap: android.graphics.Bitmap,
  /** Bitmap dimensions — in **sensor orientation**. The SurfaceView
   *  rotates this for display via [rotationDegrees] at draw time. */
  val width: Int,
  val height: Int,
  /** Sensor→display rotation as reported by CameraX
   *  `ImageInfo.rotationDegrees` (one of 0, 90, 180, 270). The
   *  [LiveTranslatorSurfaceView] applies this rotation to the
   *  drawMatrix so the displayed image lands in the natural
   *  display orientation. The Rust compositor produces the bitmap
   *  in sensor orient — the rotation only happens here, GPU-side. */
  val rotationDegrees: Int,
)

/** Thin Kotlin wrapper around `LivePlanarTracker`. Owns the camera
 *  frame pool, the display bitmap pool, the IMU service, and the
 *  per-frame state machine that dispatches the Rust acquire pipeline.
 *  Per-item overlay state, recognition, translation, content hashing,
 *  bitmap raster — all in Rust now. Kotlin's only domain here is
 *  Android lifecycle + display surface.
 */
class LivePlanarOcrEngine(
  private val catalog: LanguageCatalog,
  private val workerScope: CoroutineScope,
  @Suppress("UNUSED_PARAMETER") private val imuService: ImuService? = null,
) {
  @Volatile
  private var cameraIntrinsics: CameraIntrinsicsRaw? = null
  private val mutex = Mutex()
  private val tracker = uniffi.bindings.LivePlanarTracker()
  private var lastCropRect: NativeRect? = null

  /** EMA-smoothed homography for the currently-locked anchor. Reset on
   *  every anchor switch. Decouples render position from per-frame
   *  RANSAC noise (the ~10 px static-scene "bounce" the user was seeing). */
  private var smoothedHomography: FloatArray? = null
  private var smoothedAnchorId: ULong = 0uL

  /** Last focus point (normalized 0..1). When the user taps to focus
   *  somewhere new, we treat that as an explicit "fresh start" signal
   *  and clear the active anchor — otherwise the LRU would re-lock
   *  onto whatever it last had, which is wrong when the user is
   *  intentionally pointing at something else. */
  private var lastFocusX: Float = Float.NaN
  private var lastFocusY: Float = Float.NaN
  private var latestImuSnapshot: FloatArray? = null

  /** Set while the camera's AF is actively scanning. We don't trust
   *  features from blurred frames and we don't want to lock inliers
   *  against geometry that's about to shift when focus settles. While
   *  this is true, every frame resets the tracker so it stays in Idle;
   *  in-flight acquires bail via the generation bump. The first frame
   *  after AF settles naturally restarts the stable-window → acquire. */
  @Volatile
  private var afScanning: Boolean = false

  /** True while a `runAcquireStage` coroutine is running. We launch
   *  acquire async so the detector thread doesn't block for the
   *  ~100-150 ms of detection + initial bitmap raster (which would
   *  drop ~3 analyzer frames worth of display updates). Subsequent
   *  ACQUIRING-state frames are skipped while this is set so we don't
   *  fan out N concurrent acquires; the next `Idle → Acquiring`
   *  transition fires a fresh one. */
  private val acquireInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

  /** Pixel intrinsics from the most recent frame. Set by `runFrame`,
   *  carried through to `publishBitmapOverlay` so the view can
   *  IMU-extrapolate at refresh rate. */
  private var latestPixelIntrinsics: FrameIntrinsics? = null

  /** Live `SurfaceView` dimensions, updated via [setViewSize] from
   *  the SurfaceView's `surfaceChanged`. Used to compute the
   *  `display_crop` matching the FILL_CENTER visible region. `0`
   *  means the surface hasn't been sized yet; falls back to
   *  full-display crop. */
  private val liveViewWidth = AtomicInteger(0)
  private val liveViewHeight = AtomicInteger(0)

  fun setViewSize(
    width: Int,
    height: Int,
  ) {
    liveViewWidth.set(width.coerceAtLeast(0))
    liveViewHeight.set(height.coerceAtLeast(0))
  }

  private val pendingFrame = java.util.concurrent.atomic.AtomicReference<PendingPlanarFrame?>(null)
  private val frameSignal = Channel<Unit>(Channel.CONFLATED)
  private val handlePool = ConcurrentLinkedDeque<FrameHandle>()
  private val allocatedHandles = AtomicInteger(0)
  private val maxAllocatedHandles: Int = MAX_RETAINED_FRAME_HANDLES_PLANAR + 2

  private val detectorExecutor =
    Executors.newSingleThreadExecutor { r ->
      Thread(r, "LivePlanarOcrDetector").apply { isDaemon = true }
    }
  private val detectorDispatcher = detectorExecutor.asCoroutineDispatcher()
  private val detectorJob: Job

  private val _debugContours = MutableStateFlow<DebugContourFrame?>(null)
  val debugContours: StateFlow<DebugContourFrame?> = _debugContours.asStateFlow()

  /** Per-camera-frame composited display image (camera in display
   *  orient + overlay warped on top) emitted to the [SurfaceView]
   *  layer. Always emits on each processed analyzer frame regardless
   *  of tracker state — Idle/Lost frames emit camera-only frames so
   *  the view doesn't go black between locks. */
  private val _compositedFrame = MutableStateFlow<CompositedFrame?>(null)
  val compositedFrame: StateFlow<CompositedFrame?> = _compositedFrame.asStateFlow()

  /** Double-buffer of sensor-orient RGBA8888 bitmaps. The engine
   *  writes one while the view holds the other; we swap after each
   *  composite to avoid GC churn (an 8 MB allocation per 30 Hz frame
   *  is ~240 MB/s of garbage). The bitmap pixels are written directly
   *  by `PlanarRenderJni.compositeInto` via `AndroidBitmap_lockPixels`
   *  — no DirectByteBuffer intermediate. */
  private val displayBitmaps = arrayOfNulls<android.graphics.Bitmap>(2)
  private var displayBitmapIndex: Int = 0

  /** Bitmap pool dims. We recycle both pool slots on dim change
   *  (rare — only on rotation or camera reselection). */
  private var displayBitmapWidth: Int = 0
  private var displayBitmapHeight: Int = 0

  /** Per-frame tracker state for the debug status pill. Updated on
   *  every `processFrame` (cheap) and refined on each rec batch. */
  private val _trackerStatus =
    MutableStateFlow(
      TrackerStatus(
        state = uniffi.bindings.PlanarTrackerState.IDLE,
        anchorId = 0L,
        inliers = 0,
        lastAcquireDet = -1,
        lastAcquireRecOk = 0,
        lastAcquireRecEmpty = 0,
        lastAcquirePending = 0,
      ),
    )
  val trackerStatus: StateFlow<TrackerStatus> = _trackerStatus.asStateFlow()

  init {
    detectorJob =
      workerScope.launch(detectorDispatcher) {
        for (signal in frameSignal) {
          val frame = pendingFrame.getAndSet(null) ?: continue
          try {
            runFrame(frame)
          } catch (e: Throwable) {
            Log.w(TAG_PLANAR, "frame stage crashed", e)
            try {
              LiveFrameJni.clearExternalBuffer(frame.handle.rawAddressForJni().toLong())
            } catch (_: Throwable) {
            }
            frame.proxy?.close()
            releaseFrameHandle(frame.handle)
          }
        }
      }
  }

  fun setCameraIntrinsics(intrinsics: CameraIntrinsicsRaw?) {
    cameraIntrinsics = intrinsics
  }

  fun acquireFrameHandle(): FrameHandle? {
    handlePool.pollFirst()?.let { return it }
    if (allocatedHandles.incrementAndGet() <= maxAllocatedHandles) {
      return catalog.makeFrameBuffer(FRAME_BUFFER_CAPACITY_BYTES_PLANAR)
    }
    allocatedHandles.decrementAndGet()
    return null
  }

  fun releaseFrameHandle(handle: FrameHandle) {
    if (handlePool.size < MAX_RETAINED_FRAME_HANDLES_PLANAR) {
      handlePool.offerFirst(handle)
    } else {
      handle.close()
      allocatedHandles.decrementAndGet()
    }
  }

  fun submitFrame(
    handle: FrameHandle,
    sensorWidth: Int,
    sensorHeight: Int,
    rotationDegrees: Int,
    focusXNormalized: Float,
    focusYNormalized: Float,
    from: Language,
    to: Language,
    isAutoSource: Boolean,
    captureTimestampNs: Long,
    convertMs: Double = 0.0,
    imuRotationAtCapture: FloatArray? = null,
    proxy: androidx.camera.core.ImageProxy? = null,
  ) {
    // View dims are stashed on the engine by `setViewSize` from the
    // SurfaceView's `surfaceChanged`; we read the latest values at
    // frame-submit time. Atomic int pair (no need for synchronisation
    // — torn reads are harmless since we round to int and clamp).
    val viewWidth = liveViewWidth.get()
    val viewHeight = liveViewHeight.get()
    val newFrame =
      PendingPlanarFrame(
        handle,
        sensorWidth,
        sensorHeight,
        rotationDegrees,
        focusXNormalized,
        focusYNormalized,
        from,
        to,
        isAutoSource,
        imuRotationAtCapture,
        captureTimestampNs,
        convertMs,
        proxy,
        viewWidth,
        viewHeight,
      )
    timingProbe?.recordSubmit()
    val prev = pendingFrame.getAndSet(newFrame)
    if (prev != null) {
      timingProbe?.recordDrop()
      // Drop case: invalidate any external borrow before closing
      // the proxy (avoids the worker reading freed bytes if it
      // wakes mid-getAndSet).
      try {
        LiveFrameJni.clearExternalBuffer(prev.handle.rawAddressForJni().toLong())
      } catch (_: Throwable) {
      }
      prev.proxy?.close()
      releaseFrameHandle(prev.handle)
    }
    frameSignal.trySend(Unit)
  }

  fun onAfScanStart() {
    if (afScanning) return
    afScanning = true
    Log.i(TAG_PLANAR, "AF scan started → resetting tracker, suppressing acquire until focus settles")
    try {
      tracker.reset()
    } catch (_: Throwable) {
    }
  }

  fun onAfScanEnd() {
    if (!afScanning) return
    afScanning = false
    Log.i(TAG_PLANAR, "AF scan ended → tracker allowed to re-acquire")
  }

  fun clear() {
    // `reset()` clears engine state + overlay items + bumps Rust's
    // generation so any in-flight acquire pipeline bails. `clear()` is
    // additionally needed because `reset()` keeps the cache LRU
    // intact; `clear()` wipes that too (called on language change /
    // session teardown, not the per-tap reset path).
    tracker.reset()
    tracker.clear()
    workerScope.launch {
      mutex.withLock {
        lastCropRect = null
        smoothedHomography = null
        smoothedAnchorId = 0uL
        lastFocusX = Float.NaN
        lastFocusY = Float.NaN
        _debugContours.value = null
        _compositedFrame.value = null
      }
    }
  }

  fun shutdown() {
    detectorJob.cancel()
    detectorExecutor.shutdown()
    while (true) {
      val h = handlePool.pollFirst() ?: break
      h.close()
    }
    for (i in 0..1) {
      displayBitmaps[i]?.recycle()
      displayBitmaps[i] = null
    }
  }

  private suspend fun runFrame(pending: PendingPlanarFrame) {
    val rotation = pending.rotationDegrees
    val sensorW = pending.sensorWidth
    val sensorH = pending.sensorHeight
    // `display_crop` (the `cropRect` we pass to Rust) is the region
    // of the *display-orient* frame that the algorithm should process.
    // We restrict it to the SurfaceView's FILL_CENTER visible region
    // so what the user sees is exactly what the algorithm sees —
    // preserves the preview-as-framing-feedback contract. The fused
    // crop+rotate+RGBA→luma single-pass in Rust honours this rect
    // directly (no extra pass), so smaller crop == fewer touched
    // pixels == strictly faster.
    val cropRect =
      computeDisplayCrop(
        sensorW,
        sensorH,
        rotation,
        pending.viewWidth,
        pending.viewHeight,
      )
    // Visible-region-sensor dims: bitmap allocated by the compositor +
    // `display_width`/`display_height` passed to `processAndComposite`
    // must match the visible region (in sensor coords) so the overlay
    // surface coords land correctly when the compositor reads a centred
    // crop of the full-sensor RGBA. Swap if rotation is 90° / 270°.
    val rNorm = ((rotation % 360) + 360) % 360
    val cropDispW = (cropRect.right.toInt() - cropRect.left.toInt()).coerceAtLeast(1)
    val cropDispH = (cropRect.bottom.toInt() - cropRect.top.toInt()).coerceAtLeast(1)
    val visibleSensorW: Int
    val visibleSensorH: Int
    if (rNorm == 90 || rNorm == 270) {
      visibleSensorW = cropDispH
      visibleSensorH = cropDispW
    } else {
      visibleSensorW = cropDispW
      visibleSensorH = cropDispH
    }

    latestImuSnapshot = pending.imuRotationAtCapture
    // Tap-to-focus = "fresh start" intent. Detect it via focus-point
    // change OR crop-rect change (legacy path; with full-frame
    // detection cropRect is constant). Either way, blow away the
    // active anchor + cached overlays so the next stable frame
    // re-acquires from scratch.
    val lastCrop = lastCropRect
    val cropChanged =
      lastCrop != null && (
        lastCrop.left != cropRect.left ||
          lastCrop.top != cropRect.top ||
          lastCrop.right != cropRect.right ||
          lastCrop.bottom != cropRect.bottom
      )
    val focusJumped =
      !lastFocusX.isNaN() && (
        kotlin.math.abs(pending.focusXNormalized - lastFocusX) > FOCUS_RESET_THRESHOLD ||
          kotlin.math.abs(pending.focusYNormalized - lastFocusY) > FOCUS_RESET_THRESHOLD
      )
    if (cropChanged || focusJumped) {
      Log.i(TAG_PLANAR, "fresh-start trigger (cropChanged=$cropChanged focusJumped=$focusJumped) → resetting tracker")
      try {
        // Bumps Rust's generation → any in-flight acquire pipeline
        // bails at its next gen-check. Also clears engine state +
        // resident overlay items.
        tracker.reset()
      } catch (_: Throwable) {
      }
      mutex.withLock {
        smoothedHomography = null
        smoothedAnchorId = 0uL
      }
    }
    if (afScanning) {
      // Keep generation bumping while AF scans so any acquire that
      // started just before the scan bails on its next gen-check, and
      // so the engine's stable-window restarts on each frame and can
      // never elapse mid-scan. processAndComposite still runs so the
      // surface keeps refreshing with camera-only frames.
      try {
        tracker.reset()
      } catch (_: Throwable) {
      }
      mutex.withLock {
        smoothedHomography = null
        smoothedAnchorId = 0uL
      }
    }
    lastCropRect = cropRect
    lastFocusX = pending.focusXNormalized
    lastFocusY = pending.focusYNormalized
    val nowNs = System.nanoTime().toULong()
    // We don't have a great IMU-stable check yet; declare every frame
    // stable so initial acquire fires after the Rust-side 200ms quiet
    // window. The planar tracker tolerates small handheld motion fine.
    val imuStable = true

    // Prefer the IMU-prior path when both rotation + intrinsics are
    // available. The Rust side computes per-frame deltas from the
    // device-frame rotation and uses K·R·K^-1 as a RANSAC seed; huge
    // tracking lift on fast pans/rotations. **Sensor-orient
    // intrinsics**: the engine fits H in sensor coords now.
    val rot = pending.imuRotationAtCapture
    val intrRaw = cameraIntrinsics
    val pxIntr =
      if (intrRaw != null) {
        val p = intrRaw.sensorIntrinsics(sensorW, sensorH)
        FrameIntrinsics(p.fx, p.fy, p.cx, p.cy)
      } else {
        null
      }
    // Also cache the latest pixel intrinsics for render-thread
    // extrapolation downstream.
    latestPixelIntrinsics = pxIntr
    // One-shot: tracker step → smooth H → composite, all in Rust.
    // Avoids the per-frame Kotlin↔Rust ping-pong (two uniffi calls +
    // a Kotlin-side H-math detour) that the old split did.
    val imuRotList = if (rot != null && rot.size == 9) rot.toList() else emptyList()
    val fx = pxIntr?.fx ?: 0f
    val fy = pxIntr?.fy ?: 0f
    val cx = pxIntr?.cx ?: 0f
    val cy = pxIntr?.cy ?: 0f
    val processStartNs = System.nanoTime()
    val result =
      try {
        tracker.processAndComposite(
          pending.handle,
          cropRect,
          DETECTOR_TARGET_PIXELS_PLANAR_UINT,
          imuStable,
          nowNs,
          imuRotList,
          fx,
          fy,
          cx,
          cy,
          visibleSensorW.toUInt(),
          visibleSensorH.toUInt(),
        )
      } catch (e: Throwable) {
        Log.w(TAG_PLANAR, "processAndComposite failed", e)
        try {
          LiveFrameJni.clearExternalBuffer(pending.handle.rawAddressForJni().toLong())
        } catch (_: Throwable) {
        }
        pending.proxy?.close()
        releaseFrameHandle(pending.handle)
        return
      }
    val processMs = (System.nanoTime() - processStartNs) / 1_000_000.0
    timingProbe?.recordConvert(pending.convertMs)
    timingProbe?.recordProcess(processMs)

    framesSinceLog++
    val stateName = result.state.name
    if (stateName != lastLoggedState || framesSinceLog >= 30) {
      Log.d(
        TAG_PLANAR,
        "frame state=$stateName anchor=${result.anchorId} inliers=${result.inliers}",
      )
      lastLoggedState = stateName
      framesSinceLog = 0
    }
    _trackerStatus.value =
      _trackerStatus.value.copy(
        state = result.state,
        anchorId = result.anchorId.toLong(),
        inliers = result.inliers.toInt(),
      )
    // The uniffi `processAndComposite` call only ran the tracker step
    // and stashed `(anchor, H)` for the compositor. The actual
    // composite math runs inside the JNI `compositeInto` below,
    // writing directly into the Kotlin-owned DirectByteBuffer — no
    // intermediate Rust Vec, no JNI memcpy of bytes out.
    if (result.compositeByteSize > 0u) {
      compositeAndEmit(
        pending.handle,
        visibleSensorW,
        visibleSensorH,
        rotation,
        result.compositeByteSize.toInt(),
      )
    }
    // Decide whether an async acquire/refresh pipeline needs to
    // launch on this frame. If so, we MUST materialize the camera
    // bytes into the FrameHandle's owned storage before closing the
    // ImageProxy — the async pipeline will read bytes after this
    // function returns.
    val launchAcquire =
      result.state == PlanarTrackerState.ACQUIRING && acquireInFlight.compareAndSet(false, true)
    val launchRefresh =
      !launchAcquire &&
        result.state == PlanarTrackerState.LOCKED &&
        result.shouldRefreshDetect &&
        acquireInFlight.compareAndSet(false, true)
    val asyncWillRead = launchAcquire || launchRefresh
    val framePtr =
      try {
        pending.handle.rawAddressForJni().toLong()
      } catch (_: Throwable) {
        0L
      }
    if (asyncWillRead && framePtr != 0L) {
      // Async pipeline starting → memcpy external → owned so the
      // pipeline can read after the ImageProxy closes. This is the
      // one frame in many where we pay the input-copy cost; on
      // pure tracking frames (the common case) we skip it.
      try {
        LiveFrameJni.materializeOwned(framePtr)
      } catch (_: Throwable) {
      }
    } else if (framePtr != 0L) {
      // No async work needed — just clear the borrow so a stale
      // pointer doesn't accidentally outlive the proxy.
      try {
        LiveFrameJni.clearExternalBuffer(framePtr)
      } catch (_: Throwable) {
      }
    }
    // Close the ImageProxy back to the CameraX analyzer pool. After
    // this point, only the FrameHandle's owned `rgba` (which the
    // async pipeline materializes above) is readable.
    pending.proxy?.close()

    if (launchAcquire) {
      val capturedPending = pending
      workerScope.launch(Dispatchers.Default) {
        try {
          runAcquireStage(capturedPending, cropRect)
        } catch (e: Throwable) {
          Log.w(TAG_PLANAR, "acquire stage crashed", e)
          releaseFrameHandle(capturedPending.handle)
        } finally {
          acquireInFlight.set(false)
        }
      }
    } else if (launchRefresh) {
      val capturedPending = pending
      workerScope.launch(Dispatchers.Default) {
        try {
          runRefreshStage(capturedPending, cropRect)
        } catch (e: Throwable) {
          Log.w(TAG_PLANAR, "refresh stage crashed", e)
          releaseFrameHandle(capturedPending.handle)
        } finally {
          acquireInFlight.set(false)
        }
      }
    } else {
      releaseFrameHandle(pending.handle)
    }
  }

  private var lastLoggedState: String = ""
  private var framesSinceLog: Int = 0

  /** Per-frame timing probe — gated on `BuildConfig.DEBUG`. Counts
   *  dropped frames (analyzer submitted faster than worker drained)
   *  and accumulates per-stage durations across a 30-frame window so
   *  we can answer "is the worker keeping up with 30 fps and if not
   *  where is the time going?". One log line per ~1 s. Single-thread:
   *  all writes happen on the detector worker thread. */
  private val timingProbe =
    if (dev.davidv.translator.BuildConfig.DEBUG) FrameTimingProbe() else null

  /** Thin Kotlin shim around `tracker.runAcquirePipeline`. The whole
   *  acquire stage (detect → acquire → rec batches → batched translate
   *  → progressive overlay upserts) runs inside Rust. We just snapshot
   *  the generation for cancellation, suspend until done, and forward
   *  the resulting summary into the debug status pill. The frame
   *  handle stays alive via Kotlin's caller; we release it in the
   *  `finally`. */
  private suspend fun runAcquireStage(
    pending: PendingPlanarFrame,
    cropRect: NativeRect,
  ) {
    val handle = pending.handle
    val gen =
      try {
        tracker.currentGeneration()
      } catch (e: Throwable) {
        Log.w(TAG_PLANAR, "currentGeneration failed", e)
        return
      }
    try {
      val outcome =
        try {
          tracker.runAcquirePipeline(
            catalog.planarHandle(),
            handle,
            cropRect,
            DETECTOR_TARGET_PIXELS_PLANAR_UINT,
            ANCHOR_REGION_PAD_PX_UINT,
            System.nanoTime().toULong(),
            pending.from.code,
            pending.to.code,
            pending.isAutoSource,
            gen,
          )
        } catch (e: Throwable) {
          Log.w(TAG_PLANAR, "runAcquirePipeline crashed", e)
          return
        }
      Log.d(
        TAG_PLANAR,
        "acquire pipeline: anchor=${outcome.anchorId} det=${outcome.detectedCount} " +
          "rec_ok=${outcome.recOkCount} rec_empty=${outcome.recEmptyCount} " +
          "cache=${outcome.cacheHits} rec_called=${outcome.recCalledCount} " +
          "total=${"%.1f".format(outcome.totalMs)}ms canceled=${outcome.canceled}" +
          (outcome.error?.let { " error=$it" } ?: ""),
      )
      _trackerStatus.value =
        _trackerStatus.value.copy(
          lastAcquireDet = outcome.detectedCount.toInt(),
          lastAcquireRecOk = outcome.recOkCount.toInt(),
          lastAcquireRecEmpty = outcome.recEmptyCount.toInt(),
          lastAcquirePending = (outcome.detectedCount - outcome.recOkCount - outcome.recEmptyCount).toInt(),
        )
    } finally {
      releaseFrameHandle(handle)
    }
  }

  /** Thin Kotlin shim around `tracker.runRefreshPipeline`. Fires while
   *  the engine is Locked on an existing anchor; runs detection +
   *  surface-map ingest + rec/translate for newly-revealed text. The
   *  anchor is *not* re-acquired. */
  private suspend fun runRefreshStage(
    pending: PendingPlanarFrame,
    cropRect: NativeRect,
  ) {
    val handle = pending.handle
    val gen =
      try {
        tracker.currentGeneration()
      } catch (e: Throwable) {
        Log.w(TAG_PLANAR, "currentGeneration failed", e)
        return
      }
    try {
      val outcome =
        try {
          tracker.runRefreshPipeline(
            catalog.planarHandle(),
            handle,
            cropRect,
            DETECTOR_TARGET_PIXELS_PLANAR_UINT,
            pending.from.code,
            pending.to.code,
            pending.isAutoSource,
            gen,
          )
        } catch (e: Throwable) {
          Log.w(TAG_PLANAR, "runRefreshPipeline crashed", e)
          return
        }
      Log.d(
        TAG_PLANAR,
        "refresh pipeline: anchor=${outcome.anchorId} det=${outcome.detectedCount} " +
          "rec_ok=${outcome.recOkCount} rec_empty=${outcome.recEmptyCount} " +
          "cache=${outcome.cacheHits} rec_called=${outcome.recCalledCount} " +
          "total=${"%.1f".format(outcome.totalMs)}ms canceled=${outcome.canceled}" +
          (outcome.error?.let { " error=$it" } ?: ""),
      )
    } finally {
      releaseFrameHandle(handle)
    }
  }

  /** Push the current set of renderable entries to Rust as
   *  per-item overlay updates. Each entry becomes one
   *  `upsertOverlayItem` call; Rust hashes the content (tight box +
   *  texts + language) and skips re-rastering items whose content
   *  hasn't changed since the last upsert. After upserting, we
   *  `retainOverlayItems` to drop anything that's no longer
   *  renderable (e.g. rec returned empty for a previously-pending
   *  box).
   *
   *  This replaces the old "render one big union bitmap" path which
   *  re-rasterized every item on every batch update — on a dense
   *  page that was 130 ms × 25 batches of pointless work.

   * Build the per-camera-frame composited display image: camera RGBA
   *  rotated to display orient, with the resident overlay (if any)
   *  warped on top by [hSurfaceToViewport]. Emits via
   *  [_compositedFrame]; the [SurfaceView] subscribes and blits.
   *  Null `hSurfaceToViewport` → camera-only (no overlay this frame).
   *
   *  Reuses a 2-slot bitmap pool to avoid 8 MB allocations at 30 Hz. *

   * JNI memcpy + Bitmap copy + StateFlow emit. The actual composite
   *  (camera rotate + per-block warps) happened inside Rust during
   *  the preceding `processAndComposite` call; we only need to drain
   *  the `pending_display` slot here. */
  private fun compositeAndEmit(
    handle: FrameHandle,
    sensorWidth: Int,
    sensorHeight: Int,
    rotationDegrees: Int,
    expected: Int,
  ) {
    if (sensorWidth <= 0 || sensorHeight <= 0) return
    val bitmap = ensureDisplayBitmap(sensorWidth, sensorHeight) ?: return
    val trackerPtr =
      try {
        tracker.rawAddressForJni().toLong()
      } catch (e: Throwable) {
        Log.w(TAG_PLANAR, "rawAddressForJni failed", e)
        return
      }
    val framePtr =
      try {
        handle.rawAddressForJni().toLong()
      } catch (e: Throwable) {
        Log.w(TAG_PLANAR, "frame.rawAddressForJni failed", e)
        return
      }
    val jniStartNs = System.nanoTime()
    val written =
      try {
        PlanarRenderJni.compositeInto(trackerPtr, framePtr, bitmap, sensorWidth, sensorHeight)
      } catch (e: Throwable) {
        Log.w(TAG_PLANAR, "PlanarRenderJni.compositeInto failed", e)
        return
      }
    if (written != expected) {
      Log.w(TAG_PLANAR, "JNI composite wrote $written, expected $expected")
      return
    }
    val jniBlitMs = (System.nanoTime() - jniStartNs) / 1_000_000.0
    timingProbe?.recordCompositeJni(jniBlitMs)
    _compositedFrame.value =
      CompositedFrame(
        bitmap = bitmap,
        width = sensorWidth,
        height = sensorHeight,
        rotationDegrees = rotationDegrees,
      )
    timingProbe?.recordEmit()
    // Ping-pong: next composite writes into the other slot so the view
    // can keep drawing the bitmap we just emitted without racing.
    displayBitmapIndex = 1 - displayBitmapIndex
  }

  private fun ensureDisplayBitmap(
    width: Int,
    height: Int,
  ): android.graphics.Bitmap? {
    // Dim change: recycle both pool slots so the next composite gets
    // a freshly-sized bitmap. Rare path (rotation / camera reselect).
    if (width != displayBitmapWidth || height != displayBitmapHeight) {
      for (i in 0..1) {
        displayBitmaps[i]?.recycle()
        displayBitmaps[i] = null
      }
      displayBitmapIndex = 0
      displayBitmapWidth = width
      displayBitmapHeight = height
    }
    val slot = displayBitmapIndex
    val existing = displayBitmaps[slot]
    if (existing != null && existing.width == width && existing.height == height && !existing.isRecycled) {
      return existing
    }
    existing?.recycle()
    val fresh =
      try {
        android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
      } catch (e: Throwable) {
        Log.w(TAG_PLANAR, "createBitmap ${width}x$height failed", e)
        return null
      }
    displayBitmaps[slot] = fresh
    return fresh
  }
}

/** Aggregates per-stage frame timings over a small window so we can
 *  diagnose "is the worker thread keeping up with the camera, and if
 *  not which stage costs us the most." Writes are not synchronised —
 *  the writer is the single detector worker thread; the only
 *  exception is [recordDrop] which is called from the analyzer
 *  thread. That's racy on the long counter but the count is only ever
 *  read for an order-of-magnitude diagnostic so we accept the lost
 *  increments rather than paying lock overhead per frame. */
private class FrameTimingProbe {
  private var processMsSum: Double = 0.0
  private var processMsMax: Double = 0.0
  private var convertMsSum: Double = 0.0
  private var convertMsMax: Double = 0.0
  private var compositeJniMsSum: Double = 0.0
  private var compositeJniMsMax: Double = 0.0
  private var emitIntervalMsSum: Double = 0.0
  private var emitIntervalMsMin: Double = Double.MAX_VALUE
  private var emitIntervalMsMax: Double = 0.0
  private var framesInWindow: Int = 0
  private var lastEmitNs: Long = 0L
  private val droppedFrames = java.util.concurrent.atomic.AtomicLong(0)
  private val submittedFrames = java.util.concurrent.atomic.AtomicLong(0)

  fun recordSubmit() {
    submittedFrames.incrementAndGet()
  }

  fun recordDrop() {
    droppedFrames.incrementAndGet()
  }

  fun recordConvert(convertMs: Double) {
    convertMsSum += convertMs
    if (convertMs > convertMsMax) convertMsMax = convertMs
  }

  fun recordProcess(processMs: Double) {
    processMsSum += processMs
    if (processMs > processMsMax) processMsMax = processMs
  }

  fun recordCompositeJni(compositeJniMs: Double) {
    compositeJniMsSum += compositeJniMs
    if (compositeJniMs > compositeJniMsMax) compositeJniMsMax = compositeJniMs
  }

  fun recordEmit() {
    val nowNs = System.nanoTime()
    if (lastEmitNs != 0L) {
      val intervalMs = (nowNs - lastEmitNs) / 1_000_000.0
      emitIntervalMsSum += intervalMs
      if (intervalMs > emitIntervalMsMax) emitIntervalMsMax = intervalMs
      if (intervalMs < emitIntervalMsMin) emitIntervalMsMin = intervalMs
    }
    lastEmitNs = nowNs
    framesInWindow++
    if (framesInWindow >= 30) flush()
  }

  private fun flush() {
    val frames = framesInWindow
    if (frames == 0) return
    val avgConvert = convertMsSum / frames
    val avgProcess = processMsSum / frames
    val avgCompJni = compositeJniMsSum / frames
    val intervalSamples = frames - 1
    val avgInterval =
      if (intervalSamples > 0) emitIntervalMsSum / intervalSamples else 0.0
    val effFps = if (avgInterval > 0.0) 1000.0 / avgInterval else 0.0
    val submitted = submittedFrames.getAndSet(0)
    val dropped = droppedFrames.getAndSet(0)
    Log.i(
      "PlanarTiming",
      "%d frames | convert avg=%.1f max=%.1f | process avg=%.1f max=%.1f | jniBlit avg=%.1f max=%.1f | emit avg=%.1f min=%.1f max=%.1f → %.1f fps | submitted=%d dropped=%d".format(
        frames,
        avgConvert,
        convertMsMax,
        avgProcess,
        processMsMax,
        avgCompJni,
        compositeJniMsMax,
        avgInterval,
        if (emitIntervalMsMin == Double.MAX_VALUE) 0.0 else emitIntervalMsMin,
        emitIntervalMsMax,
        effFps,
        submitted,
        dropped,
      ),
    )
    processMsSum = 0.0
    processMsMax = 0.0
    convertMsSum = 0.0
    convertMsMax = 0.0
    compositeJniMsSum = 0.0
    compositeJniMsMax = 0.0
    emitIntervalMsSum = 0.0
    emitIntervalMsMin = Double.MAX_VALUE
    emitIntervalMsMax = 0.0
    framesInWindow = 0
  }
}
