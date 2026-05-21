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

import android.graphics.Bitmap

/** Single-call JNI fast-path for the live-camera planar OCR pipeline.
 *
 *  Per-frame entry point: [processFrame] runs the tracker step,
 *  composites the camera frame + resident overlay **directly into the
 *  destination `Bitmap`'s pixel memory** (via `AndroidBitmap_lockPixels`),
 *  and dispatches an async acquire/refresh job inside Rust when needed —
 *  all in one JNI call. Returns a packed `jlong` carrying the per-frame
 *  result so Kotlin doesn't need to make a follow-up uniffi call for
 *  the common debug-pill update.
 *
 *  Detailed async-job telemetry (rec counts, ms, cancel) is *not*
 *  packed into the per-frame return — poll
 *  `LivePlanarTracker.lastAcquireTelemetry()` when refreshing the
 *  debug pill.
 */
internal object LivePipelineJni {
  init {
    System.loadLibrary("bindings")
  }

  /** Process one camera frame. The destination [bitmap] must be
   *  `ARGB_8888` sized exactly to `visibleSensorWidth × visibleSensorHeight`
   *  with a tight stride. Returns a packed `Long` (see [State.unpack]).
   *  Returns 0 on any failure (bad pointer, wrong bitmap format,
   *  composite math error).
   */
  @JvmStatic
  external fun processFrame(
    pipelinePtr: Long,
    framePtr: Long,
    bitmap: Bitmap,
    displayCropLeft: Int,
    displayCropTop: Int,
    displayCropRight: Int,
    displayCropBottom: Int,
    visibleSensorWidth: Int,
    visibleSensorHeight: Int,
    fullViewWidth: Int,
    fullViewHeight: Int,
    imuStable: Boolean,
    timestampNs: Long,
  ): Long

  /** Decoded per-frame result (see Rust `pack_result` for the bit
   *  layout). `compositeOk == false` means no bitmap was written; the
   *  caller should not emit a new composited frame this tick. */
  data class FrameResult(
    val state: uniffi.bindings.PlanarTrackerState,
    val anchorIdLow16: Long,
    val inliers: Int,
    val compositeOk: Boolean,
    val startedAcquire: Boolean,
    val startedRefresh: Boolean,
  ) {
    companion object {
      fun unpack(packed: Long): FrameResult? {
        if (packed == 0L) return null
        val state =
          when ((packed ushr 62) and 0x3L) {
            0L -> uniffi.bindings.PlanarTrackerState.IDLE
            1L -> uniffi.bindings.PlanarTrackerState.ACQUIRING
            2L -> uniffi.bindings.PlanarTrackerState.LOCKED
            else -> uniffi.bindings.PlanarTrackerState.LOST
          }
        val compositeOk = ((packed ushr 61) and 0x1L) != 0L
        val inliers = ((packed ushr 45) and 0xFFFFL).toInt()
        val anchorLo = (packed ushr 29) and 0xFFFFL
        val startedAcquire = ((packed ushr 28) and 0x1L) != 0L
        val startedRefresh = ((packed ushr 27) and 0x1L) != 0L
        return FrameResult(state, anchorLo, inliers, compositeOk, startedAcquire, startedRefresh)
      }
    }
  }
}
