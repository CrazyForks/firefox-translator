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
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.translator.DetectedTextBox
import uniffi.translator.OrientedRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import uniffi.translator.Rect as NativeRect

private const val TAG = "LiveOcrEngine"

private const val CENTER_CROP_FRACTION = 0.8f
private const val DETECTOR_TARGET_PIXELS = 400_000.0

/** Stateless-overlay architecture: each frame's detection set is rendered as-is, with
 *  recognition results pulled from a memoization cache. The cache key is approximate
 *  (position + size + angle within tolerance); a cache miss kicks off async rec, a
 *  cache hit avoids it. Cache entries persist briefly past their last detection (to
 *  smooth detector flicker) and then evict — there's no tracker lifecycle.
 *
 *  These tolerances are deliberately tight: caching is a speed-up only, never trading
 *  correctness. A position that's drifted more than POS_TOL_FRAC of the crop width
 *  away counts as different content → fresh rec, no risk of stale text.
 */
private const val POS_TOL_FRAC = 0.07f
private const val SIZE_TOL_FRAC = 0.10f
private const val ANGLE_TOL_RAD = 0.087f
private const val GRACE_FRAMES = 1L

/** Post-rec dedup: a cache entry whose centre is within this fraction of its own width
 *  of another entry with identical (whitespace-normalised) text is collapsed into it.
 *  Catches "same content drifted just outside POS_TOL" duplicates without risking the
 *  collapse of two genuinely different lines (text equality is the gate). */
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

/** A memoized recognition result. `translatedText == null` means rec is in flight. */
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

class LiveOcrEngine(
  private val catalog: LanguageCatalog,
  private val workerScope: CoroutineScope,
) {
  private val mutex = Mutex()
  private val cache: MutableList<CacheEntry> = mutableListOf()
  private val translationCache = HashMap<TranslationKey, String>()
  private var nextId: Long = 0L
  private var frameId: Long = 0L

  /** Bumped by clear() to invalidate all in-flight workers wholesale. */
  private var globalGeneration: Long = 0L

  private val _overlays = MutableStateFlow<List<LiveOverlayItem>>(emptyList())
  val overlays: StateFlow<List<LiveOverlayItem>> = _overlays.asStateFlow()

  data class TranslationKey(val sourceCode: String, val targetCode: String, val text: String)

  /** Rolling stats. Aggregated under `mutex` and emitted as a single Log line every
   *  ~STATS_LOG_INTERVAL_NS so per-frame logs don't blast Logcat. */
  private val stats = FrameStats()
  private var statsWindowStartNs: Long = System.nanoTime()

  /** Single frame end-to-end.
   *  @param focusXNormalized 0..1 horizontal centre of the crop region.
   *  @param focusYNormalized 0..1 vertical centre of the crop region.
   */
  suspend fun submitFrame(
    fullBitmap: Bitmap,
    focusXNormalized: Float,
    focusYNormalized: Float,
    from: Language,
    to: Language,
    convertMs: Double = 0.0,
  ) = withContext(Dispatchers.Default) {
    val currentFrame: Long
    // `fullBitmap` is in display orientation (proxyToBitmap rotates the sensor frame
    // by `rotationDegrees` so the detector receives upright text).
    val frameW = fullBitmap.width
    val frameH = fullBitmap.height
    val cropW = (frameW * CENTER_CROP_FRACTION).toInt().coerceAtLeast(1)
    val cropH = (frameH * CENTER_CROP_FRACTION).toInt().coerceAtLeast(1)
    val focusFx = (focusXNormalized.coerceIn(0f, 1f) * frameW).toInt()
    val focusFy = (focusYNormalized.coerceIn(0f, 1f) * frameH).toInt()
    val cropLeft = (focusFx - cropW / 2).coerceIn(0, frameW - cropW)
    val cropTop = (focusFy - cropH / 2).coerceIn(0, frameH - cropH)

    val cropBitmap = Bitmap.createBitmap(fullBitmap, cropLeft, cropTop, cropW, cropH)
    val cropPixels = cropW.toDouble() * cropH.toDouble()
    val detScale =
      if (cropPixels > DETECTOR_TARGET_PIXELS) {
        sqrt(DETECTOR_TARGET_PIXELS / cropPixels).toFloat()
      } else {
        1f
      }
    val detW = (cropW * detScale).toInt().coerceAtLeast(1)
    val detH = (cropH * detScale).toInt().coerceAtLeast(1)
    val detBitmap =
      if (detScale < 1f) Bitmap.createScaledBitmap(cropBitmap, detW, detH, true) else cropBitmap

    val tDet = System.nanoTime()
    val detected =
      try {
        catalog.detectTextBoxes(detBitmap, from)
      } catch (e: Exception) {
        Log.w(TAG, "detect failed", e)
        if (detBitmap !== cropBitmap) detBitmap.recycle()
        cropBitmap.recycle()
        return@withContext
      }
    val detMs = (System.nanoTime() - tDet) / 1_000_000.0
    if (detBitmap !== cropBitmap) detBitmap.recycle()

    val invScale = 1f / detScale
    val cropBoxes = detected.map { scaleBoxToCrop(it, invScale, cropW, cropH) }

    val toRecognize = mutableListOf<DetectedTextBox>()
    val toRecognizeIds = mutableListOf<Long>()
    val posTolPx = cropW.toFloat() * POS_TOL_FRAC
    mutex.withLock {
      frameId++
      currentFrame = frameId

      // Each cache entry can be claimed by at most one current detection (greedy
      // bipartite). For each detection, find the closest unclaimed cache entry within
      // tolerance; if none, this detection is a fresh observation → new pending entry.
      val claimed = HashSet<Long>()
      for (box in cropBoxes) {
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
              frameWidth = frameW,
              frameHeight = frameH,
              cropOffsetX = cropLeft,
              cropOffsetY = cropTop,
            )
          cache.add(entry)
          toRecognize.add(box)
          toRecognizeIds.add(newId)
        }
      }

      // Evict cache entries not seen in the last GRACE_FRAMES. Anything older is gone
      // from the rendered overlay set, and we don't want it to influence future cache
      // lookups.
      cache.removeAll { it.lastSeenFrame < currentFrame - GRACE_FRAMES }
      publishOverlaysLocked()

      stats.record(
        convertMs = convertMs,
        detMs = detMs,
        boxes = cropBoxes.size,
        cacheHits = cropBoxes.size - toRecognize.size,
        newBoxes = toRecognize.size,
        overlayCount = _overlays.value.size,
      )
      maybeEmitStatsLocked()
    }

    if (toRecognize.isNotEmpty()) {
      scheduleRecognition(cropBitmap, toRecognize, toRecognizeIds, from, to)
    } else {
      cropBitmap.recycle()
    }
  }

  /** Mutex must be held. Emits a stats line if the rolling window has elapsed. */
  private fun maybeEmitStatsLocked() {
    val now = System.nanoTime()
    val elapsedNs = now - statsWindowStartNs
    if (elapsedNs < STATS_LOG_INTERVAL_NS) return
    val elapsedSec = elapsedNs / 1e9
    Log.i(TAG, stats.format(elapsedSec))
    stats.reset()
    statsWindowStartNs = now
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

  private fun scheduleRecognition(
    cropBitmap: Bitmap,
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
            catalog.recognizeTextInBoxes(cropBitmap, boxes, from)
          } catch (e: Exception) {
            Log.w(TAG, "recognize failed", e)
            return@launch
          }
        val recMs = (System.nanoTime() - tRec) / 1_000_000.0
        mutex.withLock { stats.recordRec(recMs) }
        // Same-length output (Rust returns one entry per input box; filtered ones
        // come back with empty text). Match by index → cache entry id.
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
        cropBitmap.recycle()
      }
    }
  }

  /** Mutex must be held. Collapses already-recognised cache entries whose translated
   *  text is identical (after whitespace normalisation) to `entry`'s, and whose centre
   *  is within `DEDUP_CENTRE_FRAC_OF_BOX_SIZE` of the entry's box size. */
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

  /** Mutex must be held. Cache entries are in display orientation already (bitmap
   *  is pre-rotated in `proxyToBitmap`), so just translate by the crop offset and
   *  emit. */
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

private val WHITESPACE = Regex("\\s+")

private fun normaliseForDedup(text: String): String = text.lowercase().replace(WHITESPACE, " ").trim()

private fun scaleBoxToCrop(
  box: DetectedTextBox,
  scale: Float,
  cropW: Int,
  cropH: Int,
): DetectedTextBox {
  val left = (box.rect.left.toFloat() * scale).toInt().coerceIn(0, cropW - 1)
  val top = (box.rect.top.toFloat() * scale).toInt().coerceIn(0, cropH - 1)
  val right = (box.rect.right.toFloat() * scale).toInt().coerceIn(left + 1, cropW)
  val bottom = (box.rect.bottom.toFloat() * scale).toInt().coerceIn(top + 1, cropH)
  val scaledOriented =
    OrientedRect(
      cx = box.orientedBox.cx * scale,
      cy = box.orientedBox.cy * scale,
      width = box.orientedBox.width * scale,
      height = box.orientedBox.height * scale,
      angleRadians = box.orientedBox.angleRadians,
    )
  val scaledContour = FloatArray(box.contour.size)
  for (i in box.contour.indices) {
    scaledContour[i] = box.contour[i] * scale
  }
  return DetectedTextBox(
    rect = NativeRect(left = left.toUInt(), top = top.toUInt(), right = right.toUInt(), bottom = bottom.toUInt()),
    orientedBox = scaledOriented,
    contour = scaledContour.toList(),
  )
}
