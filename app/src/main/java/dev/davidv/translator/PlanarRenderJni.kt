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

import java.nio.ByteBuffer

/** JNI fast-path for the planar tracker's overlay bitmap.
 *
 *  uniffi's `Vec<u8>` ↔ `ByteArray` marshalling allocates + memcpy's the
 *  whole return value through JNA. For a 960×1280 bitmap (4.6 MB) the
 *  cost is significant and we render it 3–4 times per acquire (initial
 *  + per rec batch + post-translate).
 *
 *  This shim pairs with [LivePlanarTracker.prepareTextOverlayRender]
 *  (uniffi, items in) → this `renderInto` (JNI, bytes out). Kotlin
 *  pre-allocates a `DirectByteBuffer`; Rust memcpys straight into it;
 *  Kotlin then `Bitmap.copyPixelsFromBuffer`s into the on-screen
 *  `Bitmap`. Zero uniffi marshalling, no JVM ByteArray allocation per
 *  render.
 */
internal object PlanarRenderJni {
  init {
    // uniffi loads the .so via JNA, which does NOT process JNI symbols.
    // We need an explicit System.loadLibrary so our `external fun`
    // below can resolve `Java_..._PlanarRenderJni_renderInto`.
    System.loadLibrary("bindings")
  }

  /** Copy the pending overlay bitmap (parked in the tracker by the
   *  most recent `prepareTextOverlayRender` call) into `dst`. Returns
   *  the number of bytes written, or 0 on any failure. The destination
   *  buffer must have capacity ≥ pendingBitmapByteLen.
   */
  @JvmStatic
  external fun renderInto(
    trackerPtr: Long,
    dst: ByteBuffer,
  ): Int
}
