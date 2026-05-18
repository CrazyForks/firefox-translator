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
private const val LOSS_HIDE_AFTER_FRAMES: Int = 8

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

/** Carries one camera frame through the engine. */
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
  val width: Int,
  val height: Int,
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

  /** Double-buffer of display-orient RGBA8888 bitmaps. The engine writes
   *  one while the view holds the other; we swap after each composite
   *  to avoid GC churn (an 8 MB allocation per 30 Hz frame is ~240
   *  MB/s of garbage). */
  private val displayBitmaps = arrayOfNulls<android.graphics.Bitmap>(2)
  private var displayBitmapIndex: Int = 0

  /** Direct buffer used to memcpy from Rust into the active display
   *  bitmap. Sized to the current display dims; grows on first use. */
  private var displayBuffer: java.nio.ByteBuffer? = null

  /** Display dims the engine is currently producing. We rebuild the
   *  bitmaps + buffer when these change (rare — only on rotation or
   *  camera reselection). */
  private var displayBufferWidth: Int = 0
  private var displayBufferHeight: Int = 0

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
    @Suppress("UNUSED_PARAMETER") convertMs: Double = 0.0,
    imuRotationAtCapture: FloatArray? = null,
  ) {
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
      )
    val prev = pendingFrame.getAndSet(newFrame)
    prev?.let { releaseFrameHandle(it.handle) }
    frameSignal.trySend(Unit)
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
    displayBuffer = null
  }

  private suspend fun runFrame(pending: PendingPlanarFrame) {
    val rotation = pending.rotationDegrees
    val displayW: Int
    val displayH: Int
    if (rotation == 90 || rotation == 270) {
      displayW = pending.sensorHeight
      displayH = pending.sensorWidth
    } else {
      displayW = pending.sensorWidth
      displayH = pending.sensorHeight
    }
    val cropW = (displayW * CENTER_CROP_FRACTION_PLANAR).toInt().coerceAtLeast(1)
    val cropH = (displayH * CENTER_CROP_FRACTION_PLANAR).toInt().coerceAtLeast(1)
    val focusFx = (pending.focusXNormalized.coerceIn(0f, 1f) * displayW).toInt()
    val focusFy = (pending.focusYNormalized.coerceIn(0f, 1f) * displayH).toInt()
    val cropLeft = (focusFx - cropW / 2).coerceIn(0, displayW - cropW)
    val cropTop = (focusFy - cropH / 2).coerceIn(0, displayH - cropH)
    val cropRect =
      NativeRect(
        left = cropLeft.toUInt(),
        top = cropTop.toUInt(),
        right = (cropLeft + cropW).toUInt(),
        bottom = (cropTop + cropH).toUInt(),
      )

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
    // tracking lift on fast pans/rotations.
    val rot = pending.imuRotationAtCapture
    val intrRaw = cameraIntrinsics
    val pxIntr =
      if (intrRaw != null) {
        val p = intrRaw.pixelIntrinsics(displayW, displayH, pending.rotationDegrees)
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
          displayW.toUInt(),
          displayH.toUInt(),
        )
      } catch (e: Throwable) {
        Log.w(TAG_PLANAR, "processAndComposite failed", e)
        releaseFrameHandle(pending.handle)
        return
      }

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
    // Rust already produced the composited bytes into its
    // `pending_display` slot. `compositeAndEmit` is now just the JNI
    // memcpy + Bitmap copy + StateFlow emit.
    if (result.compositeByteSize > 0u) {
      compositeAndEmit(displayW, displayH, result.compositeByteSize.toInt())
    }
    // For ACQUIRING, kick off the acquire stage on a separate worker
    // coroutine so this detector thread is free to keep processing
    // analyzer frames (composite + emit). Without this, every acquire
    // stalls the display for 3-5 frames; with it, the SurfaceView
    // keeps refreshing at full rate while detection runs in parallel.
    // Dedupe via `acquireInFlight` so we don't fan out one acquire per
    // ACQUIRING-state frame.
    if (result.state == PlanarTrackerState.ACQUIRING) {
      if (acquireInFlight.compareAndSet(false, true)) {
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
      } else {
        // Another acquire is in flight; nothing to do with this
        // frame's handle — release it.
        releaseFrameHandle(pending.handle)
      }
    } else if (result.state == PlanarTrackerState.LOCKED && result.shouldRefreshDetect) {
      // Detect-on-tracking-frame trigger: surface map says enough
      // tracked frames have elapsed since the last detection that
      // it's worth a fresh detect pass. Same in-flight dedupe as
      // the acquire branch so an acquire and a refresh don't fan
      // out together (the refresh would race the acquire's overlay
      // upserts).
      if (acquireInFlight.compareAndSet(false, true)) {
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
    } else {
      releaseFrameHandle(pending.handle)
    }
  }

  private var lastLoggedState: String = ""
  private var framesSinceLog: Int = 0

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
    displayWidth: Int,
    displayHeight: Int,
    expected: Int,
  ) {
    if (displayWidth <= 0 || displayHeight <= 0) return
    val buffer = ensureDisplayBuffer(expected, displayWidth, displayHeight)
    val bitmap = ensureDisplayBitmap(displayWidth, displayHeight) ?: return
    val trackerPtr =
      try {
        tracker.rawAddressForJni().toLong()
      } catch (e: Throwable) {
        Log.w(TAG_PLANAR, "rawAddressForJni failed", e)
        return
      }
    val written =
      try {
        PlanarRenderJni.compositeInto(trackerPtr, buffer)
      } catch (e: Throwable) {
        Log.w(TAG_PLANAR, "PlanarRenderJni.compositeInto failed", e)
        return
      }
    if (written != expected) {
      Log.w(TAG_PLANAR, "JNI composite wrote $written, expected $expected")
      return
    }
    buffer.rewind()
    bitmap.copyPixelsFromBuffer(buffer)
    _compositedFrame.value =
      CompositedFrame(bitmap = bitmap, width = displayWidth, height = displayHeight)
    // Ping-pong: next composite writes into the other slot so the view
    // can keep drawing the bitmap we just emitted without racing.
    displayBitmapIndex = 1 - displayBitmapIndex
  }

  private fun ensureDisplayBuffer(
    minBytes: Int,
    width: Int,
    height: Int,
  ): java.nio.ByteBuffer {
    val existing = displayBuffer
    val widthChanged = width != displayBufferWidth || height != displayBufferHeight
    if (existing != null && existing.capacity() >= minBytes && !widthChanged) {
      existing.clear()
      return existing
    }
    val fresh =
      java.nio.ByteBuffer
        .allocateDirect(minBytes)
        .order(java.nio.ByteOrder.nativeOrder())
    displayBuffer = fresh
    displayBufferWidth = width
    displayBufferHeight = height
    // Dims changed → existing bitmaps no longer match; drop them so
    // they get reallocated.
    for (i in 0..1) {
      displayBitmaps[i]?.recycle()
      displayBitmaps[i] = null
    }
    displayBitmapIndex = 0
    return fresh
  }

  private fun ensureDisplayBitmap(
    width: Int,
    height: Int,
  ): android.graphics.Bitmap? {
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
