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
import uniffi.bindings.PlanarOverlayInput
import uniffi.bindings.PlanarTrackerState
import uniffi.translator.OcrSourceSelection
import uniffi.translator.OrientedRect
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
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

/** Pad around the union of overlay quads when sizing the bitmap. Gives
 *  the renderer headroom to draw bg rects without clipping at the edge
 *  and absorbs small intra-page H jitter without bitmap-edge tearing. */
private const val BITMAP_BBOX_PAD_PX: Float = 32f

/** Vertical inflation around the detector's tight (ink-extent) box to
 *  make room for ascenders/descenders and small DB-mask under-coverage
 *  at glyph tops. `tight` matches the inked pixels of the contour, so
 *  for a line of all-caps the height is exactly the cap height — any
 *  descender in the rendered overlay would clip without this slack.
 *  `oriented` adds the same pad to width AND height (~25 px each side
 *  for typical 200×30 lines), which made the box 2-3× taller than the
 *  text; we want the vertical extension without the horizontal one. */
private const val TIGHT_VERTICAL_INFLATE: Float = 2.4f

/** Horizontal padding (canonical pixels) added to each side of the
 *  tight bbox when building the visual box. Keeps the rendered text
 *  from sitting flush against the rounded-rect background edge. */
private const val HORIZONTAL_PAD_PX: Float = 8f

/** Number of detected text boxes to recognise per batch. The first
 *  batch's overlays light up after one batch's recognise cost (~30ms
 *  × batch size), not after the whole page (~450ms for 15 boxes). */
private const val RECOGNIZE_BATCH_SIZE: Int = 4

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

private const val MAX_TRANSLATION_CACHE_PLANAR = 256

/** Show source text + bounding boxes instead of waiting on translations.
 *  Mirrors the legacy engine's diagnostic flag. */
private const val DEBUG_TRACKER_VIEW_PLANAR: Boolean = false

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

private data class CanonicalEntry(
  val anchorId: ULong,
  val entryId: ULong,
  val oriented: OrientedRect,
  val tight: OrientedRect,
  val sourceText: String,
  val sourceCode: String,
  val groupId: String,
  /** Has the recognizer been run on this box at least once? Distinguishes
   *  "detected, waiting for rec" (render a placeholder box so the user
   *  sees we've spotted something) from "detected, rec returned empty"
   *  (drop it — we'd be drawing a permanent meaningless box). */
  val recAttempted: Boolean = false,
)

private data class AnchorState(
  val cropLeft: Int,
  val cropTop: Int,
  val displayWidth: Int,
  val displayHeight: Int,
  val rotationDegrees: Int,
  val entries: MutableList<CanonicalEntry>,
  /** True once `prepareOverlayForComposite` has been called and the
   *  Rust-side resident overlay is in sync with these entries. False
   *  before the first rasterization, or after a failed rasterization
   *  (treated as "no overlay this anchor"). The composite pipeline
   *  reads the resident bytes from Rust each frame, so we don't keep
   *  a Kotlin Bitmap mirror. */
  var overlayPrepared: Boolean = false,
  /** Bitmap dimensions in pixels — sized to the *union of overlay
   *  quads + pad*, not the whole canonical frame. */
  var bitmapWidth: Int = 0,
  var bitmapHeight: Int = 0,
  /** Where bitmap pixel (0, 0) sits in canonical-frame coords. */
  var bitmapOriginCanonicalX: Float = 0f,
  var bitmapOriginCanonicalY: Float = 0f,
  /** Full canonical frame dims (kept around for sanity-checks and
   *  fallbacks). */
  var canonicalWidth: Int = 0,
  var canonicalHeight: Int = 0,
)

/** Drop-in replacement for [LiveOcrEngine] that drives a single
 *  per-surface homography (Rust-side `LivePlanarTracker`) instead of
 *  per-region SAD tracking. Same public API so [LiveCameraScreen] just
 *  swaps the constructor.
 *
 *  Architecturally:
 *    - process_frame → `Acquiring`: run detect + recognise + translate,
 *      then `acquire_now` + `set_overlays`, then emit overlays as-is.
 *    - process_frame → `Locked`: project cached canonical overlays
 *      through the recovered homography, emit.
 *    - process_frame → `Lost` / `Idle`: clear overlays.
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
  private val anchors: MutableMap<ULong, AnchorState> = mutableMapOf()
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
  private val translationCache = HashMap<TranslationKeyP, String>()
  private val pendingTranslations = HashSet<TranslationKeyP>()
  private var nextEntryId: ULong = 1uL
  private var globalGeneration: Long = 0L
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

  data class TranslationKeyP(val sourceCode: String, val targetCode: String, val text: String)

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
    tracker.clear()
    workerScope.launch {
      mutex.withLock {
        anchors.clear()
        pendingTranslations.clear()
        translationCache.clear()
        lastCropRect = null
        smoothedHomography = null
        smoothedAnchorId = 0uL
        lastFocusX = Float.NaN
        lastFocusY = Float.NaN
        globalGeneration++
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
        tracker.clear()
      } catch (_: Throwable) {
      }
      mutex.withLock {
        anchors.clear()
        pendingTranslations.clear()
        smoothedHomography = null
        smoothedAnchorId = 0uL
        globalGeneration++
      }
      try {
        tracker.clearOverlay()
      } catch (_: Throwable) {
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
    val result =
      try {
        if (rot != null && rot.size == 9 && pxIntr != null) {
          tracker.processFrameWithImu(
            pending.handle,
            cropRect,
            DETECTOR_TARGET_PIXELS_PLANAR_UINT,
            imuStable,
            nowNs,
            rot.toList(),
            pxIntr.fx,
            pxIntr.fy,
            pxIntr.cx,
            pxIntr.cy,
          )
        } else {
          tracker.processFrame(
            pending.handle,
            cropRect,
            DETECTOR_TARGET_PIXELS_PLANAR_UINT,
            imuStable,
            nowNs,
          )
        }
      } catch (e: Throwable) {
        Log.w(TAG_PLANAR, "planar processFrame failed", e)
        releaseFrameHandle(pending.handle)
        return
      }

    framesSinceLog++
    val stateName = result.state.name
    if (stateName != lastLoggedState || framesSinceLog >= 30) {
      Log.i(
        TAG_PLANAR,
        "frame state=$stateName anchor=${result.anchorId} inliers=${result.inliers} entries=${anchors[result.anchorId]?.entries?.size ?: 0}",
      )
      lastLoggedState = stateName
      framesSinceLog = 0
    }
    // Update the debug status pill on every frame. Cheap copy; the
    // pill renders only when DEBUG_SHOW_TRACKER_STATUS is true.
    val prev = _trackerStatus.value
    _trackerStatus.value =
      prev.copy(
        state = result.state,
        anchorId = result.anchorId.toLong(),
        inliers = result.inliers.toInt(),
      )
    // Decide what H this frame's composite should use (null = no
    // overlay; the compositor will draw camera pixels only).
    var compositeH: FloatArray? = null
    when (result.state) {
      PlanarTrackerState.IDLE -> {
        consecutiveLostFrames = 0
        try {
          tracker.clearOverlay()
        } catch (_: Throwable) {
        }
      }
      PlanarTrackerState.ACQUIRING -> {
        consecutiveLostFrames = 0
        // No overlay yet for this frame; acquire runs async below and
        // will refresh the resident overlay in time for the next frame.
      }
      PlanarTrackerState.LOCKED -> {
        consecutiveLostFrames = 0
        val anchorId = result.anchorId
        val displayed = smoothHomography(anchorId, result.homography, displayW, displayH)
        val anchorState = mutex.withLock { anchors[anchorId] }
        if (anchorState != null && anchorState.overlayPrepared) {
          compositeH = FloatArray(9).also { for (i in 0..8) it[i] = displayed[i] }
        }
      }
      PlanarTrackerState.LOST -> {
        // Transient loss: keep using the last good H so a single-frame
        // blur doesn't flicker. Sustained loss: drop the overlay.
        consecutiveLostFrames++
        val anchorId = result.anchorId
        if (consecutiveLostFrames < LOSS_HIDE_AFTER_FRAMES) {
          val anchorState = mutex.withLock { anchors[anchorId] }
          val cachedH = smoothedHomography?.takeIf { smoothedAnchorId == anchorId }
          if (anchorState != null && anchorState.overlayPrepared && cachedH != null) {
            compositeH = cachedH.copyOf()
          }
        } else {
          try {
            tracker.clearOverlay()
          } catch (_: Throwable) {
          }
        }
      }
    }
    // Composite + emit BEFORE the (potentially long) acquire stage,
    // so the user sees a fresh camera frame promptly even when we're
    // about to spend ~100-150 ms in detection.
    compositeAndEmit(pending.handle, displayW, displayH, compositeH)
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
            runAcquireStage(
              capturedPending,
              cropRect,
              cropLeft,
              cropTop,
              displayW,
              displayH,
              nowNs,
            )
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
    } else {
      releaseFrameHandle(pending.handle)
    }
  }

  private var lastLoggedState: String = ""
  private var framesSinceLog: Int = 0

  /** How many consecutive LOST frames since the last LOCKED. We hide
   *  the overlay only after a short grace period (LOSS_HIDE_AFTER_FRAMES)
   *  so a single missed frame doesn't flicker, but a sustained loss
   *  promptly clears the screen instead of leaving stale overlays
   *  floating in space. */
  private var consecutiveLostFrames: Int = 0

  /** Per-frame EMA on the homography to kill sub-pixel feature noise.
   *  We compute the max corner delta between the new and last-smoothed
   *  homography (projecting the four image corners through each) and
   *  pick an EMA `α`:
   *    - δ ≤ SMOOTH_LOW_PX  → α = SMOOTH_MIN_ALPHA  (heavy smoothing)
   *    - δ ≥ SMOOTH_HIGH_PX → α = 1.0               (snap; real motion)
   *    - in between → linear blend
   *  Real-world panning/tilting exceeds SMOOTH_HIGH_PX so it doesn't
   *  feel laggy; static-scene noise stays under SMOOTH_LOW_PX so it
   *  gets averaged out.
   *  Reset on anchor switch (cached pose may be far from current). */
  private fun smoothHomography(
    anchorId: ULong,
    incoming: List<Float>,
    frameWidth: Int,
    frameHeight: Int,
  ): List<Float> {
    if (incoming.size != 9) return incoming
    val newArr = FloatArray(9)
    for (i in 0..8) newArr[i] = incoming[i]
    val prev = smoothedHomography
    if (prev == null || smoothedAnchorId != anchorId) {
      smoothedHomography = newArr
      smoothedAnchorId = anchorId
      return incoming
    }
    val w = frameWidth.toFloat()
    val h = frameHeight.toFloat()
    val corners = arrayOf(0f to 0f, w to 0f, w to h, 0f to h)
    var maxDelta = 0f
    for ((cx, cy) in corners) {
      val pn = projectPoint(newArr, cx, cy) ?: continue
      val pp = projectPoint(prev, cx, cy) ?: continue
      val dx = pn.first - pp.first
      val dy = pn.second - pp.second
      val d = kotlin.math.hypot(dx, dy)
      if (d > maxDelta) maxDelta = d
    }
    val alpha =
      when {
        maxDelta <= SMOOTH_LOW_PX -> SMOOTH_MIN_ALPHA
        maxDelta >= SMOOTH_HIGH_PX -> 1f
        else -> {
          val t = (maxDelta - SMOOTH_LOW_PX) / (SMOOTH_HIGH_PX - SMOOTH_LOW_PX)
          SMOOTH_MIN_ALPHA + t * (1f - SMOOTH_MIN_ALPHA)
        }
      }
    val out = FloatArray(9)
    for (i in 0..8) out[i] = alpha * newArr[i] + (1f - alpha) * prev[i]
    smoothedHomography = out
    smoothedAnchorId = anchorId
    return out.toList()
  }

  private fun projectPoint(
    h: FloatArray,
    x: Float,
    y: Float,
  ): Pair<Float, Float>? {
    val qx = h[0] * x + h[1] * y + h[2]
    val qy = h[3] * x + h[4] * y + h[5]
    val qw = h[6] * x + h[7] * y + h[8]
    if (qw == 0f || !qw.isFinite()) return null
    return (qx / qw) to (qy / qw)
  }

  private suspend fun runAcquireStage(
    pending: PendingPlanarFrame,
    cropRect: NativeRect,
    cropLeft: Int,
    cropTop: Int,
    displayW: Int,
    displayH: Int,
    nowNs: ULong,
  ) {
    val handle = pending.handle
    val myGeneration = globalGeneration
    val stageStartNs = System.nanoTime()
    var handleStillOwned = true
    try {
      val tDetect = System.nanoTime()
      val detected =
        try {
          catalog.detectTextInFrame(handle, cropRect, DETECTOR_TARGET_PIXELS_PLANAR_INT)
        } catch (e: Throwable) {
          Log.w(TAG_PLANAR, "detect failed", e)
          return
        }
      val detectMs = (System.nanoTime() - tDetect) / 1_000_000.0

      Log.i(TAG_PLANAR, "acquire: detected=${detected.size}")
      if (detected.isEmpty()) return

      // Acquire the anchor on the same frame we just detected on. Do
      // this BEFORE recognise so the user sees the tracked boxes the
      // instant detection completes — recognise streams in afterwards.
      val regionRects = detected.map { it.rect }
      val anchorId =
        try {
          tracker.acquireNowInRegions(
            handle,
            cropRect,
            DETECTOR_TARGET_PIXELS_PLANAR_UINT,
            regionRects,
            ANCHOR_REGION_PAD_PX_UINT,
            nowNs,
          )
        } catch (e: Throwable) {
          Log.w(TAG_PLANAR, "acquire_now_in_regions failed", e)
          return
        }
      Log.i(TAG_PLANAR, "acquire: anchorId=$anchorId")
      if (anchorId == 0uL) {
        Log.w(TAG_PLANAR, "acquire_now returned 0 — no features or cooldown blocked")
        return
      }

      // Seed canonical entries with placeholder text — recognition fills
      // them in over the next few frames in batches of N (so a dense
      // page of 15 lines doesn't block the detector thread for 450ms).
      val defaultSourceCode = pending.from.code
      val canonicalEntries = ArrayList<CanonicalEntry>(detected.size)
      val overlayInputs = ArrayList<PlanarOverlayInput>(detected.size)
      for (det in detected) {
        val entryId = nextEntryId++
        canonicalEntries +=
          CanonicalEntry(
            anchorId = anchorId,
            entryId = entryId,
            oriented = det.orientedBox,
            tight = det.tightBox,
            sourceText = "",
            sourceCode = defaultSourceCode,
            groupId = entryId.toString(),
          )
        overlayInputs +=
          PlanarOverlayInput(
            id = entryId,
            quad = orientedCornersFlat(det.orientedBox),
            payload = "$entryId|$defaultSourceCode",
          )
      }

      try {
        tracker.setOverlays(anchorId, overlayInputs)
      } catch (e: Throwable) {
        Log.w(TAG_PLANAR, "set_overlays failed", e)
        return
      }

      // Canonical frame dims = the OrientedImage.gray we just acquired
      // against. crop width/height in display orientation.
      val canonicalW = (cropRect.right.toInt() - cropRect.left.toInt()).coerceAtLeast(1)
      val canonicalH = (cropRect.bottom.toInt() - cropRect.top.toInt()).coerceAtLeast(1)
      val anchorState =
        AnchorState(
          cropLeft = cropLeft,
          cropTop = cropTop,
          displayWidth = displayW,
          displayHeight = displayH,
          rotationDegrees = pending.rotationDegrees,
          entries = canonicalEntries,
          canonicalWidth = canonicalW,
          canonicalHeight = canonicalH,
        )
      mutex.withLock {
        if (myGeneration != globalGeneration) return
        anchors[anchorId] = anchorState
      }
      // Render the initial bitmap with cyan outlines for all detected
      // boxes (no text yet — recognise streams in below). Re-render
      // after each rec batch completes.
      val initialBitmap = renderTextBitmapForAnchor(anchorState, pending.to.code)
      if (initialBitmap != null) {
        applyRenderedBitmap(anchorState, initialBitmap)
      }
      // The next analyzer frame will compositeAndEmit with the new
      // resident overlay; no explicit publish needed here.
      val totalAcquireMs = (System.nanoTime() - stageStartNs) / 1_000_000.0
      Log.i(
        TAG_PLANAR,
        "acquire timings ms: detect=${"%.1f".format(
          detectMs,
        )} anchor=${"%.1f".format(totalAcquireMs - detectMs)} total=${"%.1f".format(totalAcquireMs)} (boxes=${detected.size})",
      )

      // Stream recognise + translate on the rec worker thread. Owns the
      // handle until done; we relinquish the `finally` close below.
      handleStillOwned = false
      runStreamingRecognize(
        handle = handle,
        cropRect = cropRect,
        anchorId = anchorId,
        detected = detected,
        canonicalEntries = canonicalEntries,
        generationAtStart = myGeneration,
        pending = pending,
      )
    } finally {
      if (handleStillOwned) {
        releaseFrameHandle(handle)
      }
    }
  }

  /** Recognise the detected boxes in batches of [RECOGNIZE_BATCH_SIZE] so
   *  the first batch's text appears in ~100ms even on a dense page.
   *  Each batch updates the corresponding canonical entries and
   *  republishes the projection. Translations are queued per batch so
   *  they don't pile up at the end. */
  private fun runStreamingRecognize(
    handle: FrameHandle,
    cropRect: NativeRect,
    anchorId: ULong,
    detected: List<uniffi.translator.DetectedTextBox>,
    canonicalEntries: List<CanonicalEntry>,
    generationAtStart: Long,
    pending: PendingPlanarFrame,
  ) {
    workerScope.launch(Dispatchers.Default) {
      try {
        val sourceSelection =
          if (pending.isAutoSource) {
            OcrSourceSelection.Auto
          } else {
            OcrSourceSelection.Specific(uniffi.translator.LanguageCode(pending.from.code))
          }
        val total = detected.size
        var startIdx = 0
        while (startIdx < total) {
          if (generationAtStart != globalGeneration) {
            Log.i(TAG_PLANAR, "streaming rec aborted: generation changed")
            return@launch
          }
          val endIdx = (startIdx + RECOGNIZE_BATCH_SIZE).coerceAtMost(total)
          val batchBoxes = detected.subList(startIdx, endIdx)
          val tBatch = System.nanoTime()
          val recognised =
            try {
              catalog.recognizeInFrame(handle, cropRect, batchBoxes, sourceSelection)
            } catch (e: Throwable) {
              Log.w(TAG_PLANAR, "recognize batch failed", e)
              return@launch
            }
          val batchMs = (System.nanoTime() - tBatch) / 1_000_000.0
          val translationsToFire = mutableListOf<TranslationKeyP>()
          mutex.withLock {
            if (generationAtStart != globalGeneration) return@launch
            val toCode = pending.to.code
            for ((local, rec) in recognised.withIndex()) {
              val canonicalIdx = startIdx + local
              if (canonicalIdx >= canonicalEntries.size) continue
              val raw = rec.text.trim()
              if (raw.isEmpty()) {
                // Mark rec as attempted so the placeholder box gets
                // dropped from future publishes — but don't continue
                // before that flag is set, otherwise the empty box
                // would linger as "pending" forever.
                val anchorStateInner = anchors[anchorId] ?: return@withLock
                val storedIdx =
                  anchorStateInner.entries.indexOfFirst {
                    it.entryId == canonicalEntries[canonicalIdx].entryId
                  }
                if (storedIdx >= 0) {
                  anchorStateInner.entries[storedIdx] =
                    anchorStateInner.entries[storedIdx].copy(recAttempted = true)
                }
                continue
              }
              val text = raw
              val srcCode =
                if (pending.isAutoSource) (rec.sourceCode ?: pending.from.code) else pending.from.code
              val newEntry =
                canonicalEntries[canonicalIdx].copy(
                  sourceText = text,
                  sourceCode = srcCode,
                  recAttempted = true,
                )
              // Mutate the anchor's entries in place. The list inside
              // AnchorState is a MutableList — safe because we own the
              // mutex.
              val anchorState = anchors[anchorId] ?: return@withLock
              // Find and replace the entry by id (the stored list may
              // have been reused across acquires).
              val storedIdx = anchorState.entries.indexOfFirst { it.entryId == newEntry.entryId }
              if (storedIdx >= 0) {
                anchorState.entries[storedIdx] = newEntry
              }
              if (!DEBUG_TRACKER_VIEW_PLANAR) {
                val key = TranslationKeyP(srcCode, toCode, text)
                if (!translationCache.containsKey(key) && key !in pendingTranslations) {
                  pendingTranslations += key
                  translationsToFire += key
                }
              }
            }
          }
          // Per-batch + cumulative summary so we can see at a glance
          // where boxes are dropping: detected (det), recognised
          // non-empty (rec_ok), recognised empty / will be discarded
          // (rec_empty), still waiting on a later batch (pending).
          val freshAnchor = mutex.withLock { anchors[anchorId] }
          val recOk = freshAnchor?.entries?.count { it.recAttempted && it.sourceText.isNotEmpty() } ?: 0
          val recEmpty = freshAnchor?.entries?.count { it.recAttempted && it.sourceText.isEmpty() } ?: 0
          val recPending = freshAnchor?.entries?.count { !it.recAttempted } ?: 0
          Log.i(
            TAG_PLANAR,
            "rec batch ${startIdx / RECOGNIZE_BATCH_SIZE + 1}/${(total + RECOGNIZE_BATCH_SIZE - 1) / RECOGNIZE_BATCH_SIZE}: " +
              "${batchBoxes.size} in ${"%.1f".format(batchMs)}ms | " +
              "det=$total rec_ok=$recOk rec_empty=$recEmpty pending=$recPending",
          )
          // Feed the debug pill with the latest acquire-stage counts.
          val prevStatus = _trackerStatus.value
          _trackerStatus.value =
            prevStatus.copy(
              lastAcquireDet = total,
              lastAcquireRecOk = recOk,
              lastAcquireRecEmpty = recEmpty,
              lastAcquirePending = recPending,
            )
          if (freshAnchor != null) {
            val rerendered = renderTextBitmapForAnchor(freshAnchor, pending.to.code)
            if (rerendered != null) {
              applyRenderedBitmap(freshAnchor, rerendered)
            }
            // The next analyzer frame composites against the refreshed
            // resident overlay; we don't publish per rec batch anymore.
          }
          if (translationsToFire.isNotEmpty()) {
            translateRequests(translationsToFire, pending.from, pending.to)
          }
          startIdx = endIdx
        }
        // After all batches: if recogniser produced zero usable text,
        // the anchor is locked to garbage (button cluster / texture /
        // unreadable text). Tracking it wastes per-frame work and the
        // LRU keeps re-locking onto it. Force a clear so the next
        // stable frame re-acquires somewhere useful.
        val finalAnchor = mutex.withLock { anchors[anchorId] }
        val finalOk = finalAnchor?.entries?.count { it.recAttempted && it.sourceText.isNotEmpty() } ?: 0
        if (finalAnchor != null && finalOk == 0) {
          Log.i(TAG_PLANAR, "anchor #$anchorId has 0 readable text after all batches → clearing")
          try {
            tracker.clear()
          } catch (_: Throwable) {
          }
          mutex.withLock {
            anchors.clear()
            smoothedHomography = null
            smoothedAnchorId = 0uL
          }
          try {
            tracker.clearOverlay()
          } catch (_: Throwable) {
          }
        }
      } finally {
        releaseFrameHandle(handle)
      }
    }
  }

  /* Phase 2: build a text overlay bitmap from the anchor's current
   *  canonical entries. Items with non-empty `sourceText` (DEBUG) or
   *  with a cached translation (production) get rendered as text via
   *  `image_render::render_overlay`; items pending recognition just
   *  appear as a cyan outline so the user sees the tracker's hit-set
   *  before text arrives.
   * Result of [renderTextBitmapForAnchor]: dimensions of the
   *  rasterized overlay and where its pixel (0, 0) sits in canonical
   *  coords. The bitmap bytes themselves live in Rust (the tracker's
   *  resident-overlay slot); the per-frame composite reads them from
   *  there directly. */
  private data class RenderedBitmap(
    val width: Int,
    val height: Int,
    val canonicalOriginX: Float,
    val canonicalOriginY: Float,
  )

  private fun renderTextBitmapForAnchor(
    anchorState: AnchorState,
    languageCode: String,
  ): RenderedBitmap? {
    val canonicalW = anchorState.canonicalWidth
    val canonicalH = anchorState.canonicalHeight
    if (canonicalW <= 0 || canonicalH <= 0) return null

    // Filter rec-failed entries up front so they don't influence the
    // union bbox calculation either.
    val renderable = anchorState.entries.filter { !(it.sourceText.isEmpty() && it.recAttempted) }
    if (renderable.isEmpty()) return null

    // Union bbox of all canonical quads + pad. Bitmap is sized to this
    // — typically a fraction of the canonical frame, dramatically
    // cheaper to rasterize and warp.
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    for (entry in renderable) {
      val q = orientedCornersFlat(visualBoxFor(entry))
      for (i in 0 until 4) {
        val x = q[i * 2]
        val y = q[i * 2 + 1]
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
      }
    }
    val pad = BITMAP_BBOX_PAD_PX
    val originX = (minX - pad).coerceAtLeast(0f)
    val originY = (minY - pad).coerceAtLeast(0f)
    val width = ((maxX + pad).coerceAtMost(canonicalW.toFloat()) - originX).toInt().coerceAtLeast(1)
    val height = ((maxY + pad).coerceAtMost(canonicalH.toFloat()) - originY).toInt().coerceAtLeast(1)

    val items =
      renderable.map { entry ->
        val text =
          when {
            entry.sourceText.isEmpty() -> ""
            DEBUG_TRACKER_VIEW_PLANAR -> entry.sourceText
            else -> translationCache[TranslationKeyP(entry.sourceCode, languageCode, entry.sourceText)] ?: ""
          }
        val visual = visualBoxFor(entry)
        val cornersCanonical = orientedCornersFlat(visual)
        val cornersLocal = FloatArray(8)
        for (i in 0 until 4) {
          cornersLocal[i * 2] = cornersCanonical[i * 2] - originX
          cornersLocal[i * 2 + 1] = cornersCanonical[i * 2 + 1] - originY
        }
        uniffi.bindings.PlanarTextRenderItem(
          id = entry.entryId,
          quad = cornersLocal.toList(),
          translatedText = text,
          sourceText = entry.sourceText,
          language = languageCode,
          // semi-transparent dark
          bgArgb = 0xC8101010.toInt().toUInt(),
          // white
          fgArgb = 0xFFFFFFFFu.toInt().toUInt(),
          suggestedFontPx = visual.height.coerceIn(10f, 120f),
        )
      }

    val tStart = System.nanoTime()
    val expected = width * height * 4
    val produced =
      try {
        tracker.prepareOverlayForComposite(
          width.toUInt(),
          height.toUInt(),
          items,
          originX,
          originY,
        ).toInt()
      } catch (e: Throwable) {
        Log.w(TAG_PLANAR, "prepareOverlayForComposite failed", e)
        return null
      }
    if (produced != expected) {
      Log.w(TAG_PLANAR, "bitmap byte size mismatch: got $produced, expected $expected")
      return null
    }
    val ms = (System.nanoTime() - tStart) / 1_000_000.0
    Log.i(
      TAG_PLANAR,
      "overlay raster: ${width}x$height (canonical ${canonicalW}x$canonicalH, origin=${originX.toInt()},${originY.toInt()}) items=${items.size} in ${"%.1f".format(
        ms,
      )}ms",
    )
    return RenderedBitmap(width, height, originX, originY)
  }

  private fun applyRenderedBitmap(
    anchorState: AnchorState,
    rendered: RenderedBitmap,
  ) {
    anchorState.overlayPrepared = true
    anchorState.bitmapWidth = rendered.width
    anchorState.bitmapHeight = rendered.height
    anchorState.bitmapOriginCanonicalX = rendered.canonicalOriginX
    anchorState.bitmapOriginCanonicalY = rendered.canonicalOriginY
  }

  /** Build the per-camera-frame composited display image: camera RGBA
   *  rotated to display orient, with the resident overlay (if any)
   *  warped on top by [hSurfaceToViewport]. Emits via
   *  [_compositedFrame]; the [SurfaceView] subscribes and blits.
   *  Null `hSurfaceToViewport` → camera-only (no overlay this frame).
   *
   *  Reuses a 2-slot bitmap pool to avoid 8 MB allocations at 30 Hz. */
  private fun compositeAndEmit(
    handle: FrameHandle,
    displayWidth: Int,
    displayHeight: Int,
    hSurfaceToViewport: FloatArray?,
  ) {
    if (displayWidth <= 0 || displayHeight <= 0) return
    val expected = displayWidth * displayHeight * 4
    val buffer = ensureDisplayBuffer(expected, displayWidth, displayHeight)
    val bitmap = ensureDisplayBitmap(displayWidth, displayHeight) ?: return
    val hList: List<Float> =
      hSurfaceToViewport?.takeIf { it.size == 9 }?.toList() ?: emptyList()
    val produced =
      try {
        tracker.compositeFrame(
          handle,
          displayWidth.toUInt(),
          displayHeight.toUInt(),
          hList,
        ).toInt()
      } catch (e: Throwable) {
        Log.w(TAG_PLANAR, "compositeFrame failed", e)
        return
      }
    if (produced != expected) {
      Log.w(TAG_PLANAR, "composite byte size mismatch: got $produced, expected $expected")
      return
    }
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

  private fun translateRequests(
    keys: List<TranslationKeyP>,
    from: Language,
    to: Language,
  ) {
    workerScope.launch(Dispatchers.Default) {
      var any = false
      for (key in keys) {
        val translated =
          try {
            catalog.translateText(from, to, key.text)
          } catch (e: Throwable) {
            Log.w(TAG_PLANAR, "translate failed for '${key.text}'", e)
            null
          }
        mutex.withLock {
          pendingTranslations.remove(key)
          if (!translated.isNullOrBlank()) {
            if (translationCache.size >= MAX_TRANSLATION_CACHE_PLANAR) {
              translationCache.keys.firstOrNull()?.let(translationCache::remove)
            }
            translationCache[key] = translated
            any = true
          }
        }
      }
      if (any) {
        val anchorId =
          try {
            tracker.currentAnchor()
          } catch (_: Throwable) {
            0uL
          }
        if (anchorId != 0uL) {
          // Translation completion changes the per-entry text → the
          // bitmap needs a re-render (production mode only; in debug
          // we already drew the source text at acquire time).
          if (!DEBUG_TRACKER_VIEW_PLANAR) {
            val freshAnchor = mutex.withLock { anchors[anchorId] }
            if (freshAnchor != null) {
              val rerendered = renderTextBitmapForAnchor(freshAnchor, to.code)
              if (rerendered != null) {
                applyRenderedBitmap(freshAnchor, rerendered)
              }
              // No publish here — next analyzer frame's compositeAndEmit
              // picks up the refreshed resident overlay.
            }
          }
        }
      }
    }
  }

  companion object {
    private val IDENTITY_H: List<Float> =
      listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
  }
}

private fun visualBoxFor(entry: CanonicalEntry): OrientedRect =
  OrientedRect(
    cx = entry.tight.cx,
    cy = entry.tight.cy,
    width = entry.tight.width + 2f * HORIZONTAL_PAD_PX,
    height = entry.tight.height * TIGHT_VERTICAL_INFLATE,
    angleRadians = entry.tight.angleRadians,
  )

/** Compute 4 corner positions (TL, TR, BR, BL) of an oriented rect, flat
 *  `[tlx, tly, trx, try, brx, bry, blx, bly]`. */
private fun orientedCornersFlat(o: OrientedRect): List<Float> {
  val c = cos(o.angleRadians)
  val s = sin(o.angleRadians)
  val hw = o.width / 2f
  val hh = o.height / 2f
  val locals =
    listOf(
      -hw to -hh,
      hw to -hh,
      hw to hh,
      -hw to hh,
    )
  val out = ArrayList<Float>(8)
  for ((lx, ly) in locals) {
    val rx = c * lx - s * ly
    val ry = s * lx + c * ly
    out += o.cx + rx
    out += o.cy + ry
  }
  return out
}
