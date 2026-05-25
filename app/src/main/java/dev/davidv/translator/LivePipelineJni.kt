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

/** Single-call JNI fast-path for the live-camera planar OCR pipeline.
 *
 *  Per-frame entry point: [processFrameGl] runs the tracker step,
 *  presents the camera frame + resident overlay straight to the bound
 *  EGL surface via the native `GlesRenderer`, and dispatches an async
 *  acquire/refresh job inside Rust when needed — all in one JNI call.
 *  Returns a packed `jlong` carrying the per-frame result so Kotlin
 *  doesn't need a follow-up uniffi call for the common debug-pill update.
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

  /** Build a native `GlesRenderer` for the GPU present path. MUST be
   *  called on the GL render thread with its EGL GLES2 context already
   *  current. Returns a native pointer (`Long`), or 0 on failure.
   *  Release with [destroyGlRenderer] on the same thread. */
  @JvmStatic
  external fun createGlRenderer(): Long

  /** Free a renderer from [createGlRenderer]. Same-thread / context as
   *  creation. */
  @JvmStatic
  external fun destroyGlRenderer(rendererPtr: Long)

  /** Runs the tracker step and presents the composite into the bound EGL
   *  surface. Call on the GL render thread; follow with `eglSwapBuffers`.
   *  Returns a packed `Long` (see [FrameResult.unpack]); `compositeOk`
   *  means a frame was drawn. */
  @JvmStatic
  external fun processFrameGl(
    pipelinePtr: Long,
    framePtr: Long,
    rendererPtr: Long,
    displayXform: FloatArray,
    displayCropLeft: Int,
    displayCropTop: Int,
    displayCropRight: Int,
    displayCropBottom: Int,
    visibleSensorWidth: Int,
    visibleSensorHeight: Int,
    fullViewWidth: Int,
    fullViewHeight: Int,
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
