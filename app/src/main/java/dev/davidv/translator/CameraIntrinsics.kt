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

import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo

private const val TAG = "CameraIntrinsics"

/** Raw camera optical parameters expressed in sensor coordinates. Pixel-space
 *  intrinsics for any given displayed image are derived per-frame via
 *  [pixelIntrinsics] because the analyzer image dimensions and rotation are
 *  only known at frame time.
 *
 *  [focalLengthMm] is the active lens focal length; on multi-camera phones
 *  CameraX picks the lens, so this is the lens currently in use. */
data class CameraIntrinsicsRaw(
  val focalLengthMm: Float,
  val sensorPhysicalWidthMm: Float,
  val sensorPhysicalHeightMm: Float,
  val sensorOrientationDegrees: Int,
) {
  /** Derives a pinhole `(fx, fy, cx, cy)` in pixels for an image of the given
   *  dimensions, displayed at [rotationDegrees] (the rotation reported by
   *  CameraX `ImageInfo`). Principal point assumed at image centre.
   *
   *  At rotation 90/270 the displayed image swaps the sensor's width and
   *  height, so the horizontal focal corresponds to the sensor's vertical
   *  physical size and vice versa. */
  fun pixelIntrinsics(
    displayWidthPx: Int,
    displayHeightPx: Int,
    rotationDegrees: Int,
  ): PixelIntrinsics {
    val rotated = rotationDegrees == 90 || rotationDegrees == 270
    val widthMmForDisplayX = if (rotated) sensorPhysicalHeightMm else sensorPhysicalWidthMm
    val heightMmForDisplayY = if (rotated) sensorPhysicalWidthMm else sensorPhysicalHeightMm
    val fx = displayWidthPx.toFloat() * focalLengthMm / widthMmForDisplayX
    val fy = displayHeightPx.toFloat() * focalLengthMm / heightMmForDisplayY
    val cx = displayWidthPx * 0.5f
    val cy = displayHeightPx * 0.5f
    return PixelIntrinsics(fx, fy, cx, cy)
  }
}

data class PixelIntrinsics(
  val fx: Float,
  val fy: Float,
  val cx: Float,
  val cy: Float,
)

/** Pull lens + sensor parameters from CameraX's Camera2 interop. Returns
 *  null on devices that don't expose the required keys (rare on modern
 *  Android, but the IMU debug path tolerates this — the crosshair stays
 *  hidden). */
@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
fun cameraIntrinsicsFromCameraX(cameraInfo: CameraInfo): CameraIntrinsicsRaw? {
  val info =
    runCatching { Camera2CameraInfo.from(cameraInfo) }
      .onFailure { Log.w(TAG, "Camera2CameraInfo unavailable", it) }
      .getOrNull() ?: return null
  val focals =
    info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
  val physical =
    info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
  val orientation =
    info.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
  if (focals == null || focals.isEmpty() || physical == null) {
    Log.w(TAG, "missing focal/physical-size characteristics; cannot build intrinsics")
    return null
  }
  return CameraIntrinsicsRaw(
    focalLengthMm = focals[0],
    sensorPhysicalWidthMm = physical.width,
    sensorPhysicalHeightMm = physical.height,
    sensorOrientationDegrees = orientation,
  )
}
