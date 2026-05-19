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

/** JNI fast-path for the planar tracker's overlay bitmap.
 *
 *  The composite math (camera blit + overlay warp) writes its output
 *  **directly into the on-screen [Bitmap]'s pixel memory** via the
 *  NDK `AndroidBitmap_lockPixels` API. Skips both the Rust-side
 *  intermediate `Vec<u8>` AND the Kotlin-side
 *  `Bitmap.copyPixelsFromBuffer` pass — one write into the bitmap
 *  per frame, no extra memcpys.
 *
 *  Pairs with [LivePlanarTracker.processAndComposite] (uniffi, runs
 *  the tracker step and stashes the H + anchor id) → this
 *  `compositeInto` (JNI, runs the composite math directly into the
 *  bitmap).
 */
internal object PlanarRenderJni {
  init {
    // uniffi loads the .so via JNA, which does NOT process JNI symbols.
    // We need an explicit System.loadLibrary so our `external fun`
    // below can resolve `Java_..._PlanarRenderJni_compositeInto`.
    System.loadLibrary("bindings")
  }

  /** Composite the camera frame + any pending overlay quads (parked
   *  by the most recent `processAndComposite` uniffi call) **directly
   *  into [bitmap]'s pixel memory** via `AndroidBitmap_lockPixels`.
   *  Returns the number of bytes written, or 0 on any failure
   *  (bad pointer, wrong bitmap format, dim mismatch, composite
   *  math error). The bitmap must be `ARGB_8888` and exactly
   *  `sensorWidth × sensorHeight` pixels.
   */
  @JvmStatic
  external fun compositeInto(
    trackerPtr: Long,
    framePtr: Long,
    bitmap: Bitmap,
    sensorWidth: Int,
    sensorHeight: Int,
  ): Int
}
