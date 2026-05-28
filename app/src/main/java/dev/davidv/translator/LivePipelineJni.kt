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
 *  Per-frame entry point: [processFrameGl] borrows the camera's
 *  `GL_TEXTURE_EXTERNAL_OES`, GPU-renders canonical luma into a small
 *  `Vec<u8>` to feed the tracker, then presents the camera + overlay
 *  composite straight to the bound EGL surface via the native
 *  `GlesRenderer` — all in one JNI call. Acquire/refresh frames read
 *  full-res RGBA back from the same texture inside the call.
 *
 *  Returns a packed `Long` so Kotlin doesn't need a follow-up uniffi
 *  call for the debug-pill update. Detailed async-job telemetry (rec
 *  counts, ms, cancel) is *not* packed in — poll
 *  `LivePlanarTracker.lastAcquireTelemetry()` to refresh the pill.
 */
internal object LivePipelineJni {
  init {
    System.loadLibrary("bindings")
  }

  /** Build a native `GlesRenderer` for the GPU present path. MUST be
   *  called on the GL render thread with its EGL GLES3 context already
   *  current. Returns a native pointer (`Long`), or 0 on failure.
   *  Release with [destroyGlRenderer] on the same thread. */
  @JvmStatic
  external fun createGlRenderer(): Long

  /** Free a renderer from [createGlRenderer]. Same-thread / context as
   *  creation. */
  @JvmStatic
  external fun destroyGlRenderer(rendererPtr: Long)

  /** Per-frame GPU path. Call on the GL render thread; follow with
   *  `eglSwapBuffers`. Returns a packed `Long` (see [FrameResult.unpack]);
   *  `compositeOk` means a frame was drawn.
   *
   *  - `cameraTexId`: id of the `GL_TEXTURE_EXTERNAL_OES` the camera's
   *    `SurfaceTexture` writes into. Must be valid for the duration of
   *    the call.
   *  - `canonicalWidth/Height`: small downscaled tracker frame size (the
   *    R8 readback the GPU produces feeds the BRIEF tracker at these
   *    dims). Capped to ~1000 on the long side; preserves surface aspect.
   *  - `surfaceWidth/Height`: EGL window surface dims; viewport for the
   *    final composite into FBO 0.
   *  - `uvXform`: row-major 3×3 mapping display UV [0,1]² → external-OES
   *    texture UV (sensor orientation). Derived from
   *    `SurfaceTexture.getTransformMatrix`.
   *  - `displayXform`: row-major 3×3 mapping canonical-pixel coords
   *    (top-left origin, y-down) → clip space. */
  @JvmStatic
  external fun processFrameGl(
    pipelinePtr: Long,
    rendererPtr: Long,
    cameraTexId: Int,
    canonicalWidth: Int,
    canonicalHeight: Int,
    surfaceWidth: Int,
    surfaceHeight: Int,
    uvXform: FloatArray,
    displayXform: FloatArray,
    timestampNs: Long,
  ): Long

  /** Decoded per-frame result (see Rust `pack_result` for the bit
   *  layout). `compositeOk == false` means no frame was drawn this
   *  tick. */
  data class FrameResult(
    val state: uniffi.bindings.PlanarTrackerState,
    val anchorIdLow16: Long,
    val inliers: Int,
    val scale: Float,
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
        val scale = (packed and 0x1FFFFFL).toFloat() / 1024f
        return FrameResult(
          state,
          anchorLo,
          inliers,
          scale,
          compositeOk,
          startedAcquire,
          startedRefresh,
        )
      }
    }
  }
}
