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

/** Number of (timestamp, quaternion) samples retained for past-time
 *  rotation lookup. 256 × 5 ms ≈ 1.28 s — comfortably more than any
 *  realistic camera-pipeline latency (capture → analyzer callback is
 *  typically 30–60 ms). Power of two so the modulo is cheap. */
private const val HISTORY_CAPACITY: Int = 256

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

  /** Ring buffer of recently integrated samples — timestamps and the
   *  quaternion state at each. Sized to cover the worst-case analyzer
   *  pipeline latency (frame capture → callback) with a safety margin.
   *  At 200 Hz a 256-slot ring holds ~1.28 s of history; analyzer
   *  latency is usually 30–60 ms so we'll typically read from the
   *  newest few entries. */
  private val historyTs = LongArray(HISTORY_CAPACITY)
  private val historyQw = FloatArray(HISTORY_CAPACITY)
  private val historyQx = FloatArray(HISTORY_CAPACITY)
  private val historyQy = FloatArray(HISTORY_CAPACITY)
  private val historyQz = FloatArray(HISTORY_CAPACITY)
  private var historyHead: Int = 0
  private var historySize: Int = 0

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
      historyHead = 0
      historySize = 0
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
      historyHead = 0
      historySize = 0
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
      historyHead = 0
      historySize = 0
    }
  }

  /** Latest integrated rotation as a row-major 3x3 matrix in body frame,
   *  optionally forward-predicted by `leadNs` past the most recent
   *  sample using the last known angular velocity. Identity until
   *  [lockBaseline] has been called and the next gyro sample has
   *  arrived. Returns a fresh array.
   *
   *  Important: `leadNs == 0` means *no* forward extrapolation — the
   *  returned matrix is the integrated state at `lastSampleNs`, *not*
   *  at wall-clock now. The previous behaviour silently predicted by
   *  `now − lastSampleNs` (up to one sample period, ~5 ms at 200 Hz)
   *  which caused visible overshoot on impulse motion: a flick lands a
   *  high-ω sample mid-impulse, and `currentRotation()` would
   *  extrapolate that spike forward for those 5 ms even after the
   *  physical motion had already decayed. Callers that actually want a
   *  display-time lead pass an explicit positive `leadNs`.
   *
   *  `leadNs` is capped at [MAX_PREDICT_SECONDS] worth of nanoseconds;
   *  beyond that we ignore it and return the un-predicted state. */
  fun currentRotation(leadNs: Long = 0L): FloatArray =
    synchronized(lock) {
      if (!baselineSet || lastSampleNs == 0L) return@synchronized quatToMat3(qw, qx, qy, qz)
      if (leadNs <= 0L) return@synchronized quatToMat3(qw, qx, qy, qz)
      val dt = (leadNs.coerceAtLeast(0L)) / 1e9f
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
      pushHistory(ts, qw, qx, qy, qz)
    }
  }

  /** Append a sample to the ring (assumes [lock] is held). Overwrites
   *  the oldest entry once we're at capacity. */
  private fun pushHistory(
    ts: Long,
    w: Float,
    x: Float,
    y: Float,
    z: Float,
  ) {
    val slot = (historyHead + historySize) % HISTORY_CAPACITY
    historyTs[slot] = ts
    historyQw[slot] = w
    historyQx[slot] = x
    historyQy[slot] = y
    historyQz[slot] = z
    if (historySize < HISTORY_CAPACITY) {
      historySize++
    } else {
      historyHead = (historyHead + 1) % HISTORY_CAPACITY
    }
  }

  /** Integrated rotation at wall time [targetNs] (same clock as
   *  `SensorEvent.timestamp` and `ImageProxy.imageInfo.timestamp` —
   *  `SystemClock.elapsedRealtimeNanos`). Slerps between the two
   *  bracketing gyro samples. Returns null when:
   *    - the service hasn't been started or no samples have arrived
   *    - [targetNs] is older than the oldest retained sample (history
   *      has aged out)
   *    - [targetNs] is more than [MAX_PREDICT_SECONDS] in the future
   *      relative to the newest sample (would require extrapolating
   *      into uncertainty — caller should fall back to
   *      [currentRotation]).
   *
   *  Use this for the camera-frame R_prev so the IMU delta covers the
   *  exact `capture → render` interval rather than `analyzer-callback
   *  → render` (which misses the analyzer pipeline latency). */
  fun rotationAt(targetNs: Long): FloatArray? =
    synchronized(lock) {
      if (historySize == 0) return@synchronized null
      val oldestSlot = historyHead
      val newestSlot = (historyHead + historySize - 1) % HISTORY_CAPACITY
      val oldestTs = historyTs[oldestSlot]
      val newestTs = historyTs[newestSlot]
      if (targetNs < oldestTs) return@synchronized null
      if (targetNs > newestTs) {
        val gapNs = targetNs - newestTs
        if ((gapNs / 1e9f) > MAX_PREDICT_SECONDS) return@synchronized null
        // Snap forward to the newest sample. The render-time caller
        // already covers the post-newest gap via currentRotation()'s
        // own extrapolation when leadNs > 0; here we just return the
        // most recent integrated state.
        return@synchronized quatToMat3(
          historyQw[newestSlot],
          historyQx[newestSlot],
          historyQy[newestSlot],
          historyQz[newestSlot],
        )
      }
      // Binary-search the bracketing samples. Ring indices wrap around
      // historyHead, so work in logical [0..historySize) and translate
      // at access time.
      var lo = 0
      var hi = historySize - 1
      while (lo < hi) {
        val mid = (lo + hi) ushr 1
        val slot = (historyHead + mid) % HISTORY_CAPACITY
        if (historyTs[slot] < targetNs) {
          lo = mid + 1
        } else {
          hi = mid
        }
      }
      val upperSlot = (historyHead + lo) % HISTORY_CAPACITY
      val upperTs = historyTs[upperSlot]
      if (upperTs == targetNs || lo == 0) {
        return@synchronized quatToMat3(
          historyQw[upperSlot],
          historyQx[upperSlot],
          historyQy[upperSlot],
          historyQz[upperSlot],
        )
      }
      val lowerSlot = (historyHead + lo - 1) % HISTORY_CAPACITY
      val lowerTs = historyTs[lowerSlot]
      val span = (upperTs - lowerTs).toFloat()
      val t = if (span > 0f) ((targetNs - lowerTs).toFloat() / span).coerceIn(0f, 1f) else 0f
      val (sw, sx, sy, sz) =
        slerp(
          historyQw[lowerSlot],
          historyQx[lowerSlot],
          historyQy[lowerSlot],
          historyQz[lowerSlot],
          historyQw[upperSlot],
          historyQx[upperSlot],
          historyQy[upperSlot],
          historyQz[upperSlot],
          t,
        )
      quatToMat3(sw, sx, sy, sz)
    }

  override fun onAccuracyChanged(
    sensor: Sensor?,
    accuracy: Int,
  ) {
  }
}

private data class Quat(val w: Float, val x: Float, val y: Float, val z: Float)

/** Shortest-arc spherical linear interpolation between two unit
 *  quaternions. Falls back to nlerp when the inputs are nearly
 *  collinear (sin θ → 0) to avoid the division blowing up. */
@Suppress("LongParameterList")
private fun slerp(
  aw: Float,
  ax: Float,
  ay: Float,
  az: Float,
  bw: Float,
  bx: Float,
  by: Float,
  bz: Float,
  t: Float,
): Quat {
  var dot = aw * bw + ax * bx + ay * by + az * bz
  var cbw = bw
  var cbx = bx
  var cby = by
  var cbz = bz
  if (dot < 0f) {
    cbw = -cbw
    cbx = -cbx
    cby = -cby
    cbz = -cbz
    dot = -dot
  }
  if (dot > 0.9995f) {
    val rw = aw + t * (cbw - aw)
    val rx = ax + t * (cbx - ax)
    val ry = ay + t * (cby - ay)
    val rz = az + t * (cbz - az)
    val n = sqrt(rw * rw + rx * rx + ry * ry + rz * rz)
    return if (n > 0f) Quat(rw / n, rx / n, ry / n, rz / n) else Quat(1f, 0f, 0f, 0f)
  }
  val theta = kotlin.math.acos(dot.coerceIn(-1f, 1f))
  val sinTheta = sin(theta)
  val wa = sin((1f - t) * theta) / sinTheta
  val wb = sin(t * theta) / sinTheta
  return Quat(
    wa * aw + wb * cbw,
    wa * ax + wb * cbx,
    wa * ay + wb * cby,
    wa * az + wb * cbz,
  )
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
