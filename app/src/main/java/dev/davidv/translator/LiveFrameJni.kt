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

/** JNI fast-path for live-OCR frame ingestion.
 *
 *  uniffi's `Vec<u8>` ↔ `ByteArray` marshalling adds two memcpy's per frame
 *  (JVM ByteArray → uniffi RustBuffer → Rust `Vec<u8>`). For a 1.5 MP frame
 *  that's ~12 MB / frame. The camera's `ImageProxy.planes[0].buffer` is already
 *  a `DirectByteBuffer` (off-JVM-heap), so this shim lets Rust `memcpy` the
 *  bytes straight from the camera buffer into the `FrameHandle`'s pre-allocated
 *  `Vec<u8>` — one copy total, native side.
 *
 *  Pair with `FrameHandle.rawAddressForJni()`: that returns the Rust heap
 *  address of the handle, which we pass back in here as `handlePtr`. The
 *  `FrameHandle` must stay alive (Kotlin holding the wrapper) for the duration
 *  of the call.
 */
internal object LiveFrameJni {
  init {
    // uniffi loads the .so via JNA, which does NOT process JNI symbols. We
    // need an explicit System.loadLibrary so our `external fun` below can
    // resolve `Java_dev_davidv_translator_LiveFrameJni_writeFrom`.
    System.loadLibrary("bindings")
  }

  /** Copy `length` bytes from `src` (a DirectByteBuffer, typically the camera's
   *  plane buffer) into the `FrameHandle` at `handlePtr`. Updates dimensions
   *  and rotation atomically and invalidates any cached oriented image.
   *
   *  @return true on success, false if anything went wrong (null/non-direct
   *    buffer, lock poisoned, length larger than buffer capacity, etc.). On
   *    failure the caller should fall back to the uniffi path or drop the
   *    frame.
   */
  @JvmStatic
  external fun writeFrom(
    handlePtr: Long,
    src: ByteBuffer,
    length: Int,
    width: Int,
    height: Int,
    rotation: Int,
  ): Boolean
}
