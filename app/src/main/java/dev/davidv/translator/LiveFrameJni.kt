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
 *  (JVM ByteArray → uniffi RustBuffer → Rust `Vec<u8>`). The camera's
 *  `ImageProxy.planes[0].buffer` is a `DirectByteBuffer` (off-JVM-heap),
 *  so this shim lets Rust memcpy or borrow the bytes directly into the
 *  `FrameHandle`'s storage.
 *
 *  Materialization of an external borrow + dropping it without copying
 *  are *not* exposed here — `LivePipelineJni.processFrame` decides
 *  per-frame whether the async pipeline needs owned bytes and calls
 *  the appropriate internal `LiveFrame` method synchronously before
 *  returning.
 */
internal object LiveFrameJni {
  init {
    System.loadLibrary("bindings")
  }

  /** Copy `length` bytes from `src` (a DirectByteBuffer, typically the
   *  camera's plane buffer) into the `FrameHandle` at `handlePtr`. Used
   *  by the stride-padded fallback path where the plane isn't a
   *  contiguous RGBA_8888 buffer.
   *
   *  @return true on success, false on any failure (null buffer, lock
   *    poisoned, length larger than buffer capacity).
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

  /** Zero-copy ingestion: record the camera DirectByteBuffer's
   *  address inside the `FrameHandle` without memcpying its bytes.
   *
   *  Lifetime contract: the caller MUST keep the backing camera
   *  `ImageProxy` alive until the subsequent
   *  `LivePipelineJni.processFrame` call returns. Inside that call
   *  the pipeline either materializes an owned copy (when launching
   *  async work) or drops the borrow (pure tracking frame). Closing
   *  the `ImageProxy` before `processFrame` returns is use-after-free.
   */
  @JvmStatic
  external fun setExternalBuffer(
    handlePtr: Long,
    src: ByteBuffer,
    length: Int,
    width: Int,
    height: Int,
    rotation: Int,
  ): Boolean
}
