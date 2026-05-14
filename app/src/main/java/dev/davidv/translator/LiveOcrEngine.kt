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

import android.util.Log
import dev.davidv.translator.ui.screens.RgbaFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.bindings.FrameHandle
import uniffi.translator.DetectedTextBox
import uniffi.translator.OrientedRect
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import uniffi.translator.Rect as NativeRect

private const val TAG = "LiveOcrEngine"

private const val CENTER_CROP_FRACTION = 0.8f

// private const val DETECTOR_TARGET_PIXELS = 400_000
private const val DETECTOR_TARGET_PIXELS = 300_000

/** Stateless-overlay architecture: each frame's detection set is rendered as-is, with
 *  recognition results pulled from a memoization cache. Cache key is approximate
 *  (position + size + angle within tolerance); a cache miss kicks off async rec, a
 *  cache hit avoids it. Tight tolerances on purpose — caching is a speed-up, not a
 *  correctness trade-off.
 */
private const val POS_TOL_FRAC = 0.07f
private const val SIZE_TOL_FRAC = 0.10f
private const val ANGLE_TOL_RAD = 0.087f
private const val GRACE_FRAMES = 1L

private const val DEDUP_CENTRE_FRAC_OF_BOX_SIZE = 0.5f

data class LiveOverlayItem(
  val cx: Float,
  val cy: Float,
  val width: Float,
  val height: Float,
  val angleRadians: Float,
  val sourceText: String,
  val translatedText: String,
  val frameWidth: Int,
  val frameHeight: Int,
)

private data class CacheEntry(
  val id: Long,
  val rect: NativeRect,
  val orientedBox: OrientedRect,
  val contour: List<Float>,
  var sourceText: String?,
  var translatedText: String?,
  var lastSeenFrame: Long,
  val frameWidth: Int,
  val frameHeight: Int,
  val cropOffsetX: Int,
  val cropOffsetY: Int,
)

/** Carries one camera frame's data through the engine's pipeline stages. The
 *  `RgbaFrame` is held until the detector stage builds a [FrameHandle] from it;
 *  conflated channel semantics mean older frames are dropped when newer ones land. */
private data class PendingFrame(
  val rgba: RgbaFrame,
  val focusXNormalized: Float,
  val focusYNormalized: Float,
  val from: Language,
  val to: Language,
  val convertMs: Double,
)

class LiveOcrEngine(
  private val catalog: LanguageCatalog,
  private val workerScope: CoroutineScope,
) {
  private val mutex = Mutex()
  private val cache: MutableList<CacheEntry> = mutableListOf()
  private val translationCache = HashMap<TranslationKey, String>()
  private var nextId: Long = 0L
  private var frameId: Long = 0L

  /** Bumped by clear() to invalidate all in-flight rec workers wholesale. */
  private var globalGeneration: Long = 0L

  private val _overlays = MutableStateFlow<List<LiveOverlayItem>>(emptyList())
  val overlays: StateFlow<List<LiveOverlayItem>> = _overlays.asStateFlow()

  data class TranslationKey(val sourceCode: String, val targetCode: String, val text: String)

  /** Frame intake channel. Conflated: only the latest frame is kept. The detector
   *  coroutine pulls from this. */
  private val frameChannel = Channel<PendingFrame>(Channel.CONFLATED)
  private val detectorExecutor =
    Executors.newSingleThreadExecutor { r ->
      Thread(r, "LiveOcrDetector").apply { isDaemon = true }
    }
  private val detectorDispatcher = detectorExecutor.asCoroutineDispatcher()
  private val detectorJob: Job

  private val stats = FrameStats()
  private var statsWindowStartNs: Long = System.nanoTime()

  init {
    detectorJob =
      workerScope.launch(detectorDispatcher) {
        for (frame in frameChannel) {
          runDetectionStage(frame)
        }
      }
  }

  /** Non-blocking handoff from the analyzer thread. Conflated — replaces any
   *  pending frame, no backpressure. */
  fun submitFrame(
    rgba: RgbaFrame,
    focusXNormalized: Float,
    focusYNormalized: Float,
    from: Language,
    to: Language,
    convertMs: Double = 0.0,
  ) {
    frameChannel.trySend(
      PendingFrame(rgba, focusXNormalized, focusYNormalized, from, to, convertMs),
    )
  }

  fun clear() {
    workerScope.launch {
      mutex.withLock {
        cache.clear()
        globalGeneration++
        publishOverlaysLocked()
      }
    }
  }

  fun shutdown() {
    detectorJob.cancel()
    detectorExecutor.shutdown()
  }

  /** Stage B (detector thread). Builds the FrameHandle, runs detect, IoU-matches
   *  against cache, kicks off async rec on Stage C for new boxes. */
  private suspend fun runDetectionStage(pending: PendingFrame) {
    val rgba = pending.rgba
    val handle: FrameHandle =
      try {
        catalog.makeFrame(rgba.rgba, rgba.width, rgba.height, rgba.rotationDegrees)
      } catch (e: Exception) {
        Log.w(TAG, "make_frame failed", e)
        return
      }

    // Compute display dimensions (post-rotation) and the crop region in display coords.
    val rotation = rgba.rotationDegrees
    val displayW: Int
    val displayH: Int
    if (rotation == 90 || rotation == 270) {
      displayW = rgba.height
      displayH = rgba.width
    } else {
      displayW = rgba.width
      displayH = rgba.height
    }
    val cropW = (displayW * CENTER_CROP_FRACTION).toInt().coerceAtLeast(1)
    val cropH = (displayH * CENTER_CROP_FRACTION).toInt().coerceAtLeast(1)
    val focusFx = (pending.focusXNormalized.coerceIn(0f, 1f) * displayW).toInt()
    val focusFy = (pending.focusYNormalized.coerceIn(0f, 1f) * displayH).toInt()
    val cropLeft = (focusFx - cropW / 2).coerceIn(0, displayW - cropW)
    val cropTop = (focusFy - cropH / 2).coerceIn(0, displayH - cropH)
    val cropRect =
      NativeRect(
        left = cropLeft.toUInt(),
        top = cropTop.toUInt(),
        right = (cropLeft + cropW).toUInt(),
        bottom = (cropTop + cropH).toUInt(),
      )

    val tDet = System.nanoTime()
    val detected =
      try {
        catalog.detectInFrame(handle, cropRect, DETECTOR_TARGET_PIXELS, pending.from)
      } catch (e: Exception) {
        Log.w(TAG, "detect failed", e)
        handle.close()
        return
      }
    val detMs = (System.nanoTime() - tDet) / 1_000_000.0

    val toRecognize = mutableListOf<DetectedTextBox>()
    val toRecognizeIds = mutableListOf<Long>()
    val posTolPx = cropW.toFloat() * POS_TOL_FRAC

    val currentFrame: Long
    mutex.withLock {
      frameId++
      currentFrame = frameId

      val claimed = HashSet<Long>()
      for (box in detected) {
        val match = bestCacheMatch(box, claimed, posTolPx)
        if (match != null) {
          claimed.add(match.id)
          match.lastSeenFrame = currentFrame
        } else {
          val newId = nextId++
          val entry =
            CacheEntry(
              id = newId,
              rect = box.rect,
              orientedBox = box.orientedBox,
              contour = box.contour,
              sourceText = null,
              translatedText = null,
              lastSeenFrame = currentFrame,
              frameWidth = displayW,
              frameHeight = displayH,
              cropOffsetX = cropLeft,
              cropOffsetY = cropTop,
            )
          cache.add(entry)
          toRecognize.add(box)
          toRecognizeIds.add(newId)
        }
      }

      cache.removeAll { it.lastSeenFrame < currentFrame - GRACE_FRAMES }
      publishOverlaysLocked()

      stats.record(
        convertMs = pending.convertMs,
        detMs = detMs,
        boxes = detected.size,
        cacheHits = detected.size - toRecognize.size,
        newBoxes = toRecognize.size,
        overlayCount = _overlays.value.size,
      )
      maybeEmitStatsLocked()
    }

    if (toRecognize.isEmpty()) {
      handle.close()
      return
    }

    // Schedule rec on the worker pool. The frame's handle is moved into the worker
    // closure so the Rust-side buffer stays alive until rec is done.
    scheduleRecognition(handle, cropRect, toRecognize, toRecognizeIds, pending.from, pending.to)
  }

  /** Stage C (rec worker, Dispatchers.Default). Recognises + translates, updates
   *  cache, publishes overlays. Closes the FrameHandle when done. */
  private fun scheduleRecognition(
    handle: FrameHandle,
    cropRect: NativeRect,
    boxes: List<DetectedTextBox>,
    entryIds: List<Long>,
    from: Language,
    to: Language,
  ) {
    val myGeneration = globalGeneration
    workerScope.launch(Dispatchers.Default) {
      try {
        val tRec = System.nanoTime()
        val recognized =
          try {
            catalog.recognizeInFrame(handle, cropRect, boxes, from)
          } catch (e: Exception) {
            Log.w(TAG, "recognize failed", e)
            return@launch
          }
        val recMs = (System.nanoTime() - tRec) / 1_000_000.0
        mutex.withLock { stats.recordRec(recMs) }
        for ((idx, line) in recognized.withIndex()) {
          val source = line.text.trim()
          if (source.isEmpty()) continue
          val translated = translateCached(from, to, source) ?: continue
          mutex.withLock {
            if (myGeneration != globalGeneration) return@launch
            if (idx >= entryIds.size) return@withLock
            val entryId = entryIds[idx]
            val entry = cache.firstOrNull { it.id == entryId }
            if (entry != null && entry.translatedText == null) {
              entry.sourceText = source
              entry.translatedText = translated
              dedupAgainstEntry(entry)
            }
          }
        }
        mutex.withLock {
          if (myGeneration == globalGeneration) publishOverlaysLocked()
        }
      } finally {
        handle.close()
      }
    }
  }

  /** Mutex must be held. */
  private fun bestCacheMatch(
    box: DetectedTextBox,
    claimed: Set<Long>,
    posTolPx: Float,
  ): CacheEntry? {
    val boxCx = (box.rect.left.toFloat() + box.rect.right.toFloat()) * 0.5f
    val boxCy = (box.rect.top.toFloat() + box.rect.bottom.toFloat()) * 0.5f
    val boxW = (box.rect.right.toFloat() - box.rect.left.toFloat()).coerceAtLeast(1f)
    val boxH = (box.rect.bottom.toFloat() - box.rect.top.toFloat()).coerceAtLeast(1f)
    var best: CacheEntry? = null
    var bestDist = Float.MAX_VALUE
    for (entry in cache) {
      if (entry.id in claimed) continue
      if (abs(entry.orientedBox.angleRadians - box.orientedBox.angleRadians) > ANGLE_TOL_RAD) continue
      val entryCx = (entry.rect.left.toFloat() + entry.rect.right.toFloat()) * 0.5f
      val entryCy = (entry.rect.top.toFloat() + entry.rect.bottom.toFloat()) * 0.5f
      val dx = abs(entryCx - boxCx)
      val dy = abs(entryCy - boxCy)
      if (dx > posTolPx || dy > posTolPx) continue
      val entryW = (entry.rect.right.toFloat() - entry.rect.left.toFloat()).coerceAtLeast(1f)
      val entryH = (entry.rect.bottom.toFloat() - entry.rect.top.toFloat()).coerceAtLeast(1f)
      if (abs(entryW - boxW) / max(entryW, boxW) > SIZE_TOL_FRAC) continue
      if (abs(entryH - boxH) / max(entryH, boxH) > SIZE_TOL_FRAC) continue
      val dist = dx + dy
      if (dist < bestDist) {
        bestDist = dist
        best = entry
      }
    }
    return best
  }

  /** Mutex must be held. Collapses already-recognised cache entries whose
   *  translated text is identical (after normalisation) to `entry`'s, and whose
   *  centre is within `DEDUP_CENTRE_FRAC_OF_BOX_SIZE` of the entry's box size. */
  private fun dedupAgainstEntry(entry: CacheEntry) {
    val translated = entry.translatedText ?: return
    val normalisedNew = normaliseForDedup(translated)
    val ecx = entry.orientedBox.cx
    val ecy = entry.orientedBox.cy
    val maxBoxSize = max(entry.orientedBox.width, entry.orientedBox.height)
    val tol = maxBoxSize * DEDUP_CENTRE_FRAC_OF_BOX_SIZE
    val victims =
      cache.filter { other ->
        if (other.id == entry.id) return@filter false
        val otherText = other.translatedText ?: return@filter false
        if (normaliseForDedup(otherText) != normalisedNew) return@filter false
        val dx = abs(other.orientedBox.cx - ecx)
        val dy = abs(other.orientedBox.cy - ecy)
        dx <= tol && dy <= tol
      }
    if (victims.isNotEmpty()) {
      stats.dedupEvictions += victims.size
      cache.removeAll(victims)
    }
  }

  /** Mutex must be held. */
  private fun publishOverlaysLocked() {
    _overlays.value =
      cache.filter { it.translatedText != null }.map { entry ->
        LiveOverlayItem(
          cx = entry.orientedBox.cx + entry.cropOffsetX,
          cy = entry.orientedBox.cy + entry.cropOffsetY,
          width = entry.orientedBox.width,
          height = entry.orientedBox.height,
          angleRadians = entry.orientedBox.angleRadians,
          sourceText = entry.sourceText.orEmpty(),
          translatedText = entry.translatedText!!,
          frameWidth = entry.frameWidth,
          frameHeight = entry.frameHeight,
        )
      }
  }

  /** Mutex must be held. */
  private fun maybeEmitStatsLocked() {
    val now = System.nanoTime()
    val elapsedNs = now - statsWindowStartNs
    if (elapsedNs < STATS_LOG_INTERVAL_NS) return
    val elapsedSec = elapsedNs / 1e9
    Log.i(TAG, stats.format(elapsedSec))
    stats.reset()
    statsWindowStartNs = now
  }

  private fun translateCached(
    from: Language,
    to: Language,
    text: String,
  ): String? {
    val key = TranslationKey(from.code, to.code, text)
    translationCache[key]?.let { return it }
    val translated =
      try {
        catalog.translateText(from, to, text)
      } catch (e: Exception) {
        Log.w(TAG, "translate failed for '$text'", e)
        return null
      }
    if (translated.isBlank()) return null
    if (translationCache.size >= MAX_TRANSLATION_CACHE) {
      translationCache.keys.firstOrNull()?.let(translationCache::remove)
    }
    translationCache[key] = translated
    return translated
  }

  companion object {
    private const val MAX_TRANSLATION_CACHE = 256
    private const val STATS_LOG_INTERVAL_NS: Long = 5_000_000_000L
  }
}

private val WHITESPACE = Regex("\\s+")

private fun normaliseForDedup(text: String): String = text.lowercase().replace(WHITESPACE, " ").trim()

private class FrameStats {
  var frames: Int = 0
  var convertMsSum: Double = 0.0
  var convertMsMax: Double = 0.0
  var detMsSum: Double = 0.0
  var detMsMax: Double = 0.0
  var recMsSum: Double = 0.0
  var recMsMax: Double = 0.0
  var recCalls: Int = 0
  var boxesSum: Int = 0
  var cacheHitsSum: Int = 0
  var newBoxesSum: Int = 0
  var overlayCountSum: Int = 0
  var dedupEvictions: Int = 0

  fun record(
    convertMs: Double,
    detMs: Double,
    boxes: Int,
    cacheHits: Int,
    newBoxes: Int,
    overlayCount: Int,
  ) {
    frames += 1
    convertMsSum += convertMs
    if (convertMs > convertMsMax) convertMsMax = convertMs
    detMsSum += detMs
    if (detMs > detMsMax) detMsMax = detMs
    boxesSum += boxes
    cacheHitsSum += cacheHits
    newBoxesSum += newBoxes
    overlayCountSum += overlayCount
  }

  fun recordRec(recMs: Double) {
    recCalls += 1
    recMsSum += recMs
    if (recMs > recMsMax) recMsMax = recMs
  }

  fun format(elapsedSec: Double): String {
    val n = frames.coerceAtLeast(1)
    val fps = frames.toDouble() / elapsedSec.coerceAtLeast(1e-6)
    val convertAvg = convertMsSum / n
    val detAvg = detMsSum / n
    val recAvg = if (recCalls > 0) recMsSum / recCalls else 0.0
    val boxesAvg = boxesSum.toDouble() / n
    val cacheHitPct = if (boxesSum > 0) 100.0 * cacheHitsSum / boxesSum else 0.0
    val newPerFrame = newBoxesSum.toDouble() / n
    val overlayAvg = overlayCountSum.toDouble() / n
    return "live stats: %d frames in %.1fs (%.1f fps) | conv avg/max=%.0f/%.0f ms | det avg/max=%.0f/%.0f ms | rec calls=%d avg/max=%.0f/%.0f ms | boxes/frame=%.1f cacheHit=%.0f%% new/frame=%.1f overlays=%.1f dedup=%d".format(
      frames,
      elapsedSec,
      fps,
      convertAvg,
      convertMsMax,
      detAvg,
      detMsMax,
      recCalls,
      recAvg,
      recMsMax,
      boxesAvg,
      cacheHitPct,
      newPerFrame,
      overlayAvg,
      dedupEvictions,
    )
  }

  fun reset() {
    frames = 0
    convertMsSum = 0.0
    convertMsMax = 0.0
    detMsSum = 0.0
    detMsMax = 0.0
    recMsSum = 0.0
    recMsMax = 0.0
    recCalls = 0
    boxesSum = 0
    cacheHitsSum = 0
    newBoxesSum = 0
    overlayCountSum = 0
    dedupEvictions = 0
  }
}
