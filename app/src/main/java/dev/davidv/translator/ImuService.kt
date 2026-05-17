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

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "ImuService"

/** 200 Hz. Above 200 Hz Android 12+ requires the HIGH_SAMPLING_RATE_SENSORS
 *  permission (added to gate loudspeaker-vibration eavesdropping research).
 *  200 Hz is plenty for predicting between 30 Hz camera frames. */
private const val GYRO_SAMPLE_PERIOD_US: Int = 5_000

/** Cap forward-prediction horizon. If the last sample is older than this
 *  (sensor stalled, app backgrounded, etc.) we fall back to the un-predicted
 *  quaternion rather than extrapolating into uncertainty. */
private const val MAX_PREDICT_SECONDS: Float = 0.1f

/** Tracks orientation drift since [lockBaseline] by integrating gyroscope
 *  samples in the device body frame. Output is a unit quaternion `q` such
 *  that for a fixed world vector `v0` whose representation in the body frame
 *  at lock time was `v0`, the representation in the body frame at the latest
 *  sample is `R(q)^T * v0`.
 *
 *  Pure rotation tracking — no translation, no accelerometer fusion, no
 *  gravity reference. Drift accumulates over time (gyro bias), so callers
 *  should re-lock periodically. For Phase 1 (debug crosshair smoke test)
 *  the user re-locks manually.
 *
 *  Thread safety: [SensorManager] callbacks fire on the registered handler
 *  thread (main, here). Readers can call [currentRotation] from any thread;
 *  reads are protected by the same lock that the listener uses to mutate. */
class ImuService(
  context: Context,
) : SensorEventListener {
  private val sensorManager =
    context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
  private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

  private val lock = Any()
  private var lastSampleNs: Long = 0L
  private var baselineSet: Boolean = false

  private var qw: Float = 1f
  private var qx: Float = 0f
  private var qy: Float = 0f
  private var qz: Float = 0f

  private var lastOmegaX: Float = 0f
  private var lastOmegaY: Float = 0f
  private var lastOmegaZ: Float = 0f

  fun hasGyro(): Boolean = gyroSensor != null

  fun start() {
    val sensor = gyroSensor
    if (sensor == null) {
      Log.w(TAG, "no gyroscope on this device")
      return
    }
    val ok = sensorManager.registerListener(this, sensor, GYRO_SAMPLE_PERIOD_US)
    if (!ok) Log.w(TAG, "registerListener returned false for gyroscope")
    synchronized(lock) {
      qw = 1f
      qx = 0f
      qy = 0f
      qz = 0f
      baselineSet = true
    }
  }

  fun stop() {
    sensorManager.unregisterListener(this)
    synchronized(lock) {
      lastSampleNs = 0L
      baselineSet = false
      qw = 1f
      qx = 0f
      qy = 0f
      qz = 0f
      lastOmegaX = 0f
      lastOmegaY = 0f
      lastOmegaZ = 0f
    }
  }

  /** Reset the integrated orientation to identity. The crosshair (or any
   *  consumer) treats "now" as the new reference; subsequent
   *  [currentRotation] queries return the drift since this call. */
  fun lockBaseline() {
    synchronized(lock) {
      qw = 1f
      qx = 0f
      qy = 0f
      qz = 0f
      baselineSet = true
    }
  }

  /** Latest integrated rotation as a row-major 3x3 matrix in body frame,
   *  forward-predicted from the most recent sample's timestamp to
   *  `now + leadNs` using the last known angular velocity (assumed constant
   *  over the short prediction horizon). Identity until [lockBaseline] has
   *  been called and the next gyro sample has arrived. Returns a fresh
   *  array. `leadNs` is a positive lead time used by render-thread callers
   *  to predict to the *display* moment (~vsync), not just sensor-read
   *  moment. Snapshot callers (engine solver prior) pass 0. */
  fun currentRotation(leadNs: Long = 0L): FloatArray =
    synchronized(lock) {
      if (!baselineSet || lastSampleNs == 0L) return@synchronized quatToMat3(qw, qx, qy, qz)
      val targetNs = SystemClock.elapsedRealtimeNanos() + leadNs
      val dt = ((targetNs - lastSampleNs).coerceAtLeast(0L)) / 1e9f
      if (dt <= 0f || dt > MAX_PREDICT_SECONDS) return@synchronized quatToMat3(qw, qx, qy, qz)
      val omega = sqrt(lastOmegaX * lastOmegaX + lastOmegaY * lastOmegaY + lastOmegaZ * lastOmegaZ)
      if (omega < 1e-6f) return@synchronized quatToMat3(qw, qx, qy, qz)
      val theta = omega * dt
      val half = theta * 0.5f
      val s = sin(half) / omega
      val dw = cos(half)
      val dx = lastOmegaX * s
      val dy = lastOmegaY * s
      val dz = lastOmegaZ * s
      val pw = qw * dw - qx * dx - qy * dy - qz * dz
      val px = qw * dx + qx * dw + qy * dz - qz * dy
      val py = qw * dy - qx * dz + qy * dw + qz * dx
      val pz = qw * dz + qx * dy - qy * dx + qz * dw
      quatToMat3(pw, px, py, pz)
    }

  override fun onSensorChanged(event: SensorEvent) {
    if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
    synchronized(lock) {
      val ts = event.timestamp
      if (lastSampleNs == 0L || !baselineSet) {
        lastSampleNs = ts
        return
      }
      val dt = (ts - lastSampleNs) / 1e9f
      lastSampleNs = ts
      if (dt <= 0f || dt > 0.1f) return

      val wx = event.values[0]
      val wy = event.values[1]
      val wz = event.values[2]
      lastOmegaX = wx
      lastOmegaY = wy
      lastOmegaZ = wz
      val omega = sqrt(wx * wx + wy * wy + wz * wz)
      if (omega < 1e-6f) return

      val theta = omega * dt
      val half = theta * 0.5f
      val s = sin(half) / omega
      val dw = cos(half)
      val dx = wx * s
      val dy = wy * s
      val dz = wz * s

      val nw = qw * dw - qx * dx - qy * dy - qz * dz
      val nx = qw * dx + qx * dw + qy * dz - qz * dy
      val ny = qw * dy - qx * dz + qy * dw + qz * dx
      val nz = qw * dz + qx * dy - qy * dx + qz * dw

      val n = sqrt(nw * nw + nx * nx + ny * ny + nz * nz)
      if (n > 0f) {
        val inv = 1f / n
        qw = nw * inv
        qx = nx * inv
        qy = ny * inv
        qz = nz * inv
      }
    }
  }

  override fun onAccuracyChanged(
    sensor: Sensor?,
    accuracy: Int,
  ) {
  }
}

private fun quatToMat3(
  w: Float,
  x: Float,
  y: Float,
  z: Float,
): FloatArray {
  val xx = x * x
  val yy = y * y
  val zz = z * z
  val xy = x * y
  val xz = x * z
  val yz = y * z
  val wx = w * x
  val wy = w * y
  val wz = w * z
  return floatArrayOf(
    1f - 2f * (yy + zz), 2f * (xy - wz), 2f * (xz + wy),
    2f * (xy + wz), 1f - 2f * (xx + zz), 2f * (yz - wx),
    2f * (xz - wy), 2f * (yz + wx), 1f - 2f * (xx + yy),
  )
}
