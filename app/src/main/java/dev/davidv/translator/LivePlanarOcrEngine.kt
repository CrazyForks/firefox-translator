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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.bindings.FrameHandle
import uniffi.bindings.PipelineTargetMode
import uniffi.bindings.PlanarTrackerState
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import uniffi.translator.Rect as NativeRect

private const val FRAME_BUFFER_CAPACITY_BYTES_PLANAR: Int = 8 * 1024 * 1024
private const val MAX_RETAINED_FRAME_HANDLES_PLANAR: Int = 4
private const val TAG_PLANAR = "LivePlanarOcrEngine"

/** Compute the algorithm's `display_crop` from the SurfaceView's
 *  FILL_CENTER visible region. Mirrors `LiveTranslatorSurfaceView.drawComposited`
 *  scale math. */
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

/** Normalized-coord threshold to treat a focus-point change as an
 *  explicit "user re-aimed" event. */
private const val FOCUS_RESET_THRESHOLD: Float = 0.05f

/** Debug status snapshot for the on-screen tracker pill. */
data class TrackerStatus(
  val state: PlanarTrackerState,
  val anchorId: Long,
  val inliers: Int,
  val lastAcquireDet: Int,
  val lastAcquireRecOk: Int,
  val lastAcquireRecEmpty: Int,
  val lastAcquirePending: Int,
)

/** One per-camera-frame composited display image. */
data class CompositedFrame(
  val bitmap: android.graphics.Bitmap,
  val width: Int,
  val height: Int,
  val rotationDegrees: Int,
)

/** Thin Kotlin host for the Rust `LiveTrackerPipeline`. Owns the
 *  display bitmap pool + frame handle pool + camera-side focus/AF
 *  state. Per-frame work — tracker step, composite, async acquire/
 *  refresh dispatch — all happens inside Rust via a single
 *  [LivePipelineJni.processFrame] call. */
class LivePlanarOcrEngine(
  private val catalog: LanguageCatalog,
  @Suppress("UNUSED_PARAMETER") workerScope: CoroutineScope,
) {
  private val tracker = uniffi.bindings.LivePlanarTracker(catalog.planarHandle())

  private val handlePool = ConcurrentLinkedDeque<FrameHandle>()
  private val allocatedHandles = AtomicInteger(0)
  private val maxAllocatedHandles: Int = MAX_RETAINED_FRAME_HANDLES_PLANAR + 2

  private val liveViewWidth = AtomicInteger(0)
  private val liveViewHeight = AtomicInteger(0)

  private val displayBitmaps = arrayOfNulls<android.graphics.Bitmap>(2)
  private var displayBitmapIndex: Int = 0
  private var displayBitmapWidth: Int = 0
  private var displayBitmapHeight: Int = 0

  private val _compositedFrame = MutableStateFlow<CompositedFrame?>(null)
  val compositedFrame: StateFlow<CompositedFrame?> = _compositedFrame.asStateFlow()

  private val _trackerStatus =
    MutableStateFlow(
      TrackerStatus(
        state = PlanarTrackerState.IDLE,
        anchorId = 0L,
        inliers = 0,
        lastAcquireDet = -1,
        lastAcquireRecOk = 0,
        lastAcquireRecEmpty = 0,
        lastAcquirePending = 0,
      ),
    )
  val trackerStatus: StateFlow<TrackerStatus> = _trackerStatus.asStateFlow()

  // Focus + language state tracked here so we can `tracker.reset()` /
  // `setLanguages()` only on actual change, not every frame.
  private var lastFocusX: Float = Float.NaN
  private var lastFocusY: Float = Float.NaN
  private var lastFromCode: String = ""
  private var lastToCode: String = ""
  private var lastIsAutoSource: Boolean = false

  @Volatile
  private var afScanning: Boolean = false

  @Volatile
  private var overlayEnabled: Boolean = true

  fun setViewSize(
    width: Int,
    height: Int,
  ) {
    liveViewWidth.set(width.coerceAtLeast(0))
    liveViewHeight.set(height.coerceAtLeast(0))
  }

  fun acquireFrameHandle(): FrameHandle? {
    // Skip "busy" pooled entries (those whose `LiveFrame` is still
    // held by an in-flight pipeline worker job — overwriting their
    // state would race the worker's read).
    val skipped = mutableListOf<FrameHandle>()
    while (true) {
      val pooled = handlePool.pollFirst() ?: break
      if (!pooled.isBusy()) {
        // Put back the skipped (still-busy) ones in reverse order so
        // we re-check them next acquire (cheapest non-busy first).
        for (s in skipped.asReversed()) handlePool.offerFirst(s)
        return pooled
      }
      skipped += pooled
    }
    // Put busy ones back; they'll free up shortly.
    for (s in skipped.asReversed()) handlePool.offerFirst(s)
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

  /** Per-frame entry. Called from the CameraX analyzer thread (single-
   *  threaded by construction). Runs the entire frame synchronously,
   *  including the JNI tracker+composite call. Acquire/refresh
   *  pipelines dispatch *internally* inside Rust on the pipeline's
   *  worker thread; this call returns as soon as the bitmap is
   *  written. */
  fun processFrame(
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
  ) {
    // Language config changes → push into pipeline (cheap; just
    // updates a mutex-protected struct on the Rust side).
    if (from.code != lastFromCode || to.code != lastToCode || isAutoSource != lastIsAutoSource) {
      try {
        tracker.setLanguages(from.code, to.code, isAutoSource)
      } catch (_: Throwable) {
      }
      lastFromCode = from.code
      lastToCode = to.code
      lastIsAutoSource = isAutoSource
    }

    val cropRect =
      computeDisplayCrop(
        sensorWidth,
        sensorHeight,
        rotationDegrees,
        liveViewWidth.get(),
        liveViewHeight.get(),
      )
    val rNorm = ((rotationDegrees % 360) + 360) % 360
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

    // Tap-to-focus = fresh-start intent. Resetting the tracker bumps
    // the Rust generation so any in-flight async job bails at its
    // next gen-check.
    val focusJumped =
      !lastFocusX.isNaN() && (
        kotlin.math.abs(focusXNormalized - lastFocusX) > FOCUS_RESET_THRESHOLD ||
          kotlin.math.abs(focusYNormalized - lastFocusY) > FOCUS_RESET_THRESHOLD
      )
    if (focusJumped) {
      try {
        tracker.reset()
      } catch (_: Throwable) {
      }
    }
    lastFocusX = focusXNormalized
    lastFocusY = focusYNormalized

    val bitmap = ensureDisplayBitmap(visibleSensorW, visibleSensorH) ?: return
    val pipelinePtr =
      try {
        tracker.rawAddressForJni().toLong()
      } catch (e: Throwable) {
        Log.w(TAG_PLANAR, "tracker.rawAddressForJni failed", e)
        return
      }
    val framePtr =
      try {
        handle.rawAddressForJni().toLong()
      } catch (e: Throwable) {
        Log.w(TAG_PLANAR, "handle.rawAddressForJni failed", e)
        return
      }
    val packed =
      try {
        LivePipelineJni.processFrame(
          pipelinePtr,
          framePtr,
          bitmap,
          cropRect.left.toInt(),
          cropRect.top.toInt(),
          cropRect.right.toInt(),
          cropRect.bottom.toInt(),
          visibleSensorW,
          visibleSensorH,
          sensorWidth,
          sensorHeight,
          true,
          captureTimestampNs,
        )
      } catch (e: Throwable) {
        Log.w(TAG_PLANAR, "LivePipelineJni.processFrame failed", e)
        return
      }
    val result = LivePipelineJni.FrameResult.unpack(packed)
    if (result != null && result.compositeOk) {
      _compositedFrame.value =
        CompositedFrame(
          bitmap = bitmap,
          width = visibleSensorW,
          height = visibleSensorH,
          rotationDegrees = rotationDegrees,
        )
      displayBitmapIndex = 1 - displayBitmapIndex
    }

    // Tracker pill: state + inliers come back in the packed result;
    // detailed acquire/refresh outcome (only present after a worker
    // job completes) is pulled separately.
    if (result != null) {
      val baseStatus =
        _trackerStatus.value.copy(
          state = result.state,
          anchorId = result.anchorIdLow16,
          inliers = result.inliers,
        )
      val telemetry =
        try {
          tracker.lastAcquireTelemetry()
        } catch (_: Throwable) {
          null
        }
      _trackerStatus.value =
        if (telemetry != null) {
          baseStatus.copy(
            anchorId = telemetry.anchorId.toLong(),
            lastAcquireDet = telemetry.detectedCount.toInt(),
            lastAcquireRecOk = telemetry.recOkCount.toInt(),
            lastAcquireRecEmpty = telemetry.recEmptyCount.toInt(),
            lastAcquirePending =
              (telemetry.detectedCount - telemetry.recOkCount - telemetry.recEmptyCount).toInt(),
          )
        } else {
          baseStatus
        }
    }
  }

  fun onAfScanStart() {
    if (afScanning) return
    afScanning = true
    Log.i(TAG_PLANAR, "AF scan started → suspending pipeline")
    applyTargetMode()
  }

  fun onAfScanEnd() {
    if (!afScanning) return
    afScanning = false
    Log.i(TAG_PLANAR, "AF scan ended → resuming pipeline")
    applyTargetMode()
  }

  /** Toggle whether OCR/translation overlay work runs. When disabled,
   *  the tracker is held in SUPPRESSED so per-frame composites still
   *  produce camera pixels but no acquire/refresh worker is dispatched.
   *  This lets the camera keep rendering when models are missing or
   *  the user has turned the overlay off. */
  fun setOverlayEnabled(enabled: Boolean) {
    if (overlayEnabled == enabled) return
    overlayEnabled = enabled
    applyTargetMode()
  }

  private fun applyTargetMode() {
    val mode =
      if (overlayEnabled && !afScanning) PipelineTargetMode.ACTIVE else PipelineTargetMode.SUPPRESSED
    try {
      tracker.setTargetMode(mode)
    } catch (_: Throwable) {
    }
  }

  fun clear() {
    try {
      tracker.reset()
    } catch (_: Throwable) {
    }
    try {
      tracker.clearOverlay()
    } catch (_: Throwable) {
    }
    lastFocusX = Float.NaN
    lastFocusY = Float.NaN
    _compositedFrame.value = null
  }

  fun shutdown() {
    while (true) {
      val h = handlePool.pollFirst() ?: break
      h.close()
    }
    for (i in 0..1) {
      displayBitmaps[i]?.recycle()
      displayBitmaps[i] = null
    }
  }

  private fun ensureDisplayBitmap(
    width: Int,
    height: Int,
  ): android.graphics.Bitmap? {
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
