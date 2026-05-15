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
import uniffi.translator.OcrSourceSelection
import uniffi.translator.OrientedRect
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import uniffi.translator.Rect as NativeRect

/** Buffer size we allocate per [FrameHandle]. Covers a 1.5 MP RGBA frame
 *  comfortably (~6 MB) with a small slack for safety. The Rust-side `Vec<u8>`
 *  is allocated once at this capacity and reused frame-to-frame. */
private const val FRAME_BUFFER_CAPACITY_BYTES: Int = 8 * 1024 * 1024
private const val MAX_RETAINED_FRAME_HANDLES: Int = 4

private const val TAG = "LiveOcrEngine"

private const val CENTER_CROP_FRACTION = 0.8f

private const val DETECTOR_TARGET_PIXELS = 450_000
private const val DETECTOR_INTERVAL_NS: Long = 200_000_000L

/** Detector observations are now corrections to stable visual tracks. Matching
 *  tolerances are deliberately looser than the old cache because frame-to-frame
 *  tracking predicts where the text should be before the detector result lands. */
private const val POS_TOL_FRAC = 0.12f
private const val SIZE_TOL_FRAC = 0.25f
private const val ANGLE_TOL_RAD = 0.22f
private const val DETECTOR_CORRECTION_ALPHA = 0.22f
private const val DETECTOR_CORRECTION_ALPHA_UNTRACKED = 0.45f
private const val MAX_DETECTOR_MISSES = 2L
private const val MAX_UNTRACKED_FRAMES = 4L

private const val DEDUP_CENTRE_FRAC_OF_BOX_SIZE = 0.5f

private const val REC_FREEZE_CONFIDENCE = 0.85f
private const val REC_MAX_ATTEMPTS = 3
private const val REC_REPLACE_EPSILON = 0.02f

data class LiveOverlayItem(
  val groupId: String,
  val cx: Float,
  val cy: Float,
  val width: Float,
  val height: Float,
  val angleRadians: Float,
  val sourceText: String,
  val translatedText: String,
  val groupText: String,
  val frameWidth: Int,
  val frameHeight: Int,
)

private data class TrackEntry(
  val id: Long,
  var rect: NativeRect,
  var orientedBox: OrientedRect,
  var tightBox: OrientedRect,
  var sourceText: String?,
  var recognitionConfidence: Float,
  var recognitionAttempts: Int,
  var detectorScore: Float,
  var recognitionPending: Boolean,
  var detectorHits: Int,
  var lastVisualFrame: Long,
  var lastMatchedDetection: Long,
  var frameWidth: Int,
  var frameHeight: Int,
  var sourceCode: String?,
)

private data class LiveRenderGroup(
  val id: String,
  val tracks: List<TrackEntry>,
  val sourceText: String,
  val sourceCode: String,
)

/** Carries one camera frame through the engine's pipeline stages. The [FrameHandle]
 *  is the canonical Rust-owned RGBA buffer (already filled by the analyzer via
 *  [LiveFrameJni.writeFrom]); the engine releases it back to the pool when the
 *  detector + rec worker are both done. */
private data class PendingFrame(
  val handle: FrameHandle,
  val sensorWidth: Int,
  val sensorHeight: Int,
  val rotationDegrees: Int,
  val focusXNormalized: Float,
  val focusYNormalized: Float,
  val from: Language,
  val to: Language,
  val isAutoSource: Boolean,
  val convertMs: Double,
)

class LiveOcrEngine(
  private val catalog: LanguageCatalog,
  private val workerScope: CoroutineScope,
) {
  private val mutex = Mutex()
  private val tracks: MutableList<TrackEntry> = mutableListOf()
  private val translationCache = HashMap<TranslationKey, String>()
  private val pendingGroupTranslations = HashSet<TranslationKey>()
  private var nextId: Long = 0L
  private var frameId: Long = 0L
  private var detectionId: Long = 0L
  private var lastDetectionNs: Long = 0L
  private var lastCropRect: NativeRect? = null
  private var activeSourceCode: String? = null
  private var activeTargetCode: String? = null

  /** Bumped by clear() to invalidate all in-flight rec workers wholesale. */
  private var globalGeneration: Long = 0L

  private val _overlays = MutableStateFlow<List<LiveOverlayItem>>(emptyList())
  val overlays: StateFlow<List<LiveOverlayItem>> = _overlays.asStateFlow()

  data class TranslationKey(val sourceCode: String, val targetCode: String, val text: String)

  private data class GroupTranslationRequest(
    val key: TranslationKey,
    val from: Language,
    val to: Language,
  )

  /** Manual conflation: only the latest frame is held; if a new one arrives while
   *  another is pending, the previous one's [FrameHandle] is returned to the
   *  pool. `frameSignal` is just a wake-up notification — the actual payload
   *  lives in `pendingFrame`. */
  private val pendingFrame = java.util.concurrent.atomic.AtomicReference<PendingFrame?>(null)
  private val frameSignal = Channel<Unit>(Channel.CONFLATED)
  private val handlePool = ConcurrentLinkedDeque<FrameHandle>()

  /** Owned (allocated by us) [FrameHandle]s in flight or in the pool. Limited
   *  so a runaway burst doesn't allocate unbounded Rust-side buffers. */
  private val allocatedHandles = AtomicInteger(0)
  private val maxAllocatedHandles: Int = MAX_RETAINED_FRAME_HANDLES + 2

  private val detectorExecutor =
    Executors.newSingleThreadExecutor { r ->
      Thread(r, "LiveOcrDetector").apply { isDaemon = true }
    }
  private val detectorDispatcher = detectorExecutor.asCoroutineDispatcher()
  private val detectorJob: Job

  private val stats = FrameStats()
  private var statsWindowStartNs: Long = System.nanoTime()
  private val motionTracker = uniffi.bindings.LiveMotionTracker()

  init {
    detectorJob =
      workerScope.launch(detectorDispatcher) {
        for (signal in frameSignal) {
          val frame = pendingFrame.getAndSet(null) ?: continue
          // runDetectionStage releases the handle: either directly (no work for
          // rec) or via the rec worker's `finally`.
          runDetectionStage(frame)
        }
      }
  }

  /** Pop a [FrameHandle] from the pool, allocating one on first use until the
   *  pool steady-state is reached. Returns `null` if we've already allocated
   *  the max and none are free (caller should drop the frame). */
  fun acquireFrameHandle(): FrameHandle? {
    handlePool.pollFirst()?.let { return it }
    if (allocatedHandles.incrementAndGet() <= maxAllocatedHandles) {
      return catalog.makeFrameBuffer(FRAME_BUFFER_CAPACITY_BYTES)
    }
    allocatedHandles.decrementAndGet()
    return null
  }

  /** Return a [FrameHandle] to the pool, or close it if the pool is already
   *  full. Safe to call from any thread. */
  fun releaseFrameHandle(handle: FrameHandle) {
    if (handlePool.size < MAX_RETAINED_FRAME_HANDLES) {
      handlePool.offerFirst(handle)
    } else {
      handle.close()
      allocatedHandles.decrementAndGet()
    }
  }

  /** Non-blocking handoff from the analyzer thread. The engine takes ownership
   *  of `handle`; if a previous frame was pending, its handle is returned to
   *  the pool. */
  fun submitFrame(
    handle: FrameHandle,
    sensorWidth: Int,
    sensorHeight: Int,
    rotationDegrees: Int,
    focusXNormalized: Float,
    focusYNormalized: Float,
    from: Language,
    to: Language,
    isAutoSource: Boolean,
    convertMs: Double = 0.0,
  ) {
    val newFrame =
      PendingFrame(
        handle,
        sensorWidth,
        sensorHeight,
        rotationDegrees,
        focusXNormalized,
        focusYNormalized,
        from,
        to,
        isAutoSource,
        convertMs,
      )
    val replaced = pendingFrame.getAndSet(newFrame)
    if (replaced != null) releaseFrameHandle(replaced.handle)
    frameSignal.trySend(Unit)
  }

  fun clear() {
    motionTracker.reset()
    workerScope.launch {
      mutex.withLock {
        tracks.clear()
        pendingGroupTranslations.clear()
        lastCropRect = null
        lastDetectionNs = 0L
        activeSourceCode = null
        activeTargetCode = null
        globalGeneration++
        publishOverlaysLocked()
      }
    }
  }

  fun shutdown() {
    detectorJob.cancel()
    detectorExecutor.shutdown()
    while (true) {
      val h = handlePool.pollFirst() ?: break
      h.close()
    }
  }

  /** Stage B (detector thread). First updates visual tracks from frame-to-frame
   *  crop motion, then periodically runs PPOCR detection as a correction /
   *  re-acquisition source. New or still-unresolved tracks kick off async rec.
   *  Hands off the handle to the rec worker (which releases it on completion);
   *  if there's no rec work, releases the handle directly. */
  private suspend fun runDetectionStage(pending: PendingFrame) {
    val handle = pending.handle

    val rotation = pending.rotationDegrees
    val displayW: Int
    val displayH: Int
    if (rotation == 90 || rotation == 270) {
      displayW = pending.sensorHeight
      displayH = pending.sensorWidth
    } else {
      displayW = pending.sensorWidth
      displayH = pending.sensorHeight
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

    val cropChanged =
      lastCropRect?.let {
        it.left != cropRect.left ||
          it.top != cropRect.top ||
          it.right != cropRect.right ||
          it.bottom != cropRect.bottom
      } ?: false
    if (cropChanged) motionTracker.reset()
    lastCropRect = cropRect

    val trackSnapshot: List<Pair<Long, NativeRect>> =
      mutex.withLock { tracks.map { it.id to it.rect } }
    val motionUpdate =
      try {
        motionTracker.updateWithRegions(handle, cropRect, trackSnapshot.map { it.second })
      } catch (e: Exception) {
        Log.w(TAG, "motion tracking failed", e)
        null
      }

    val nowNs = System.nanoTime()
    val currentFrame: Long
    var shouldDetect: Boolean
    mutex.withLock {
      frameId++
      currentFrame = frameId
      activeSourceCode = if (pending.isAutoSource) null else pending.from.code
      activeTargetCode = pending.to.code
      if (cropChanged) {
        tracks.clear()
      } else {
        applyMotionLocked(motionUpdate, trackSnapshot, displayW, displayH)
      }
      pruneTracksLocked(currentFrame)
      publishOverlaysLocked()
      val hasRenderableTrack = tracks.any { isRenderableTrack(it) }
      val hasPendingTrack = tracks.any { it.recognitionPending }
      shouldDetect =
        lastDetectionNs == 0L ||
        nowNs - lastDetectionNs >= DETECTOR_INTERVAL_NS ||
        (!hasRenderableTrack && !hasPendingTrack)
    }

    if (!shouldDetect) {
      releaseFrameHandle(handle)
      return
    }

    val tDet = System.nanoTime()
    val detected =
      try {
        catalog.detectTextInFrame(handle, cropRect, DETECTOR_TARGET_PIXELS)
      } catch (e: Exception) {
        Log.w(TAG, "detect failed", e)
        releaseFrameHandle(handle)
        return
      }
    val detMs = (System.nanoTime() - tDet) / 1_000_000.0
    lastDetectionNs = System.nanoTime()

    val toRecognize = mutableListOf<DetectedTextBox>()
    val toRecognizeIds = mutableListOf<Long>()
    val posTolPx = cropW.toFloat() * POS_TOL_FRAC
    val correctionAlpha =
      if (motionUpdate?.global?.valid == true) DETECTOR_CORRECTION_ALPHA else DETECTOR_CORRECTION_ALPHA_UNTRACKED

    val groupRequests =
      mutex.withLock {
        detectionId++
        val currentDetection = detectionId

        val claimed = HashSet<Long>()
        for (box in detected) {
          val fullBox = offsetDetectedBox(box, cropLeft, cropTop)
          val match = bestTrackMatch(fullBox, claimed, posTolPx)
          if (match != null) {
            claimed.add(match.id)
            correctTrackGeometry(match, fullBox, correctionAlpha, displayW, displayH)
            match.lastVisualFrame = currentFrame
            match.lastMatchedDetection = currentDetection
            match.detectorHits += 1
            match.detectorScore = fullBox.score
            match.frameWidth = displayW
            match.frameHeight = displayH
            if (
              !match.recognitionPending &&
              match.recognitionAttempts < REC_MAX_ATTEMPTS &&
              (match.sourceText == null || match.recognitionConfidence < REC_FREEZE_CONFIDENCE)
            ) {
              match.recognitionPending = true
              toRecognize.add(box)
              toRecognizeIds.add(match.id)
            }
          } else {
            val newId = nextId++
            val entry =
              TrackEntry(
                id = newId,
                rect = fullBox.rect,
                orientedBox = fullBox.orientedBox,
                tightBox = fullBox.tightBox,
                sourceText = null,
                recognitionConfidence = 0f,
                recognitionAttempts = 0,
                detectorScore = fullBox.score,
                recognitionPending = true,
                detectorHits = 1,
                lastVisualFrame = currentFrame,
                lastMatchedDetection = currentDetection,
                frameWidth = displayW,
                frameHeight = displayH,
                sourceCode = if (pending.isAutoSource) null else pending.from.code,
              )
            tracks.add(entry)
            toRecognize.add(box)
            toRecognizeIds.add(newId)
          }
        }

        tracks.removeAll {
          currentDetection - it.lastMatchedDetection > MAX_DETECTOR_MISSES
        }
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
        collectMissingGroupTranslationsLocked(pending.to)
      }
    translateGroupRequests(groupRequests)

    if (toRecognize.isEmpty()) {
      releaseFrameHandle(handle)
      return
    }

    // Schedule rec on the worker pool. The handle is owned by the worker until
    // it finishes (it releases back to the pool in `finally`).
    scheduleRecognition(handle, cropRect, toRecognize, toRecognizeIds, pending.from, pending.to, pending.isAutoSource)
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
    isAutoSource: Boolean,
  ) {
    val myGeneration = globalGeneration
    workerScope.launch(Dispatchers.Default) {
      try {
        val tRec = System.nanoTime()
        val recognized =
          try {
            val sourceSelection =
              if (isAutoSource) {
                OcrSourceSelection.Auto
              } else {
                OcrSourceSelection.Specific(uniffi.translator.LanguageCode(from.code))
              }
            catalog.recognizeInFrame(handle, cropRect, boxes, sourceSelection)
          } catch (e: Exception) {
            Log.w(TAG, "recognize failed", e)
            mutex.withLock {
              entryIds.forEach { entryId ->
                tracks.firstOrNull { it.id == entryId }?.recognitionPending = false
              }
            }
            return@launch
          }
        val recMs = (System.nanoTime() - tRec) / 1_000_000.0
        mutex.withLock { stats.recordRec(recMs) }
        mutex.withLock {
          if (myGeneration != globalGeneration) return@launch
          val byId = tracks.associateBy { it.id }
          for ((idx, line) in recognized.withIndex()) {
            if (idx >= entryIds.size) break
            val entry = byId[entryIds[idx]] ?: continue
            entry.recognitionPending = false
            entry.recognitionAttempts += 1
            val source = line.text.trim()
            if (source.isEmpty()) continue
            val better =
              entry.sourceText == null ||
                line.confidence > entry.recognitionConfidence + REC_REPLACE_EPSILON
            if (!better) continue
            entry.sourceText = source
            entry.sourceCode = if (isAutoSource) line.sourceCode else from.code
            entry.recognitionConfidence = line.confidence
            dedupAgainstEntry(entry)
          }
        }
        val requests =
          mutex.withLock {
            if (myGeneration == globalGeneration) {
              collectMissingGroupTranslationsLocked(to)
            } else {
              emptyList()
            }
          }
        translateGroupRequests(requests)
        mutex.withLock {
          if (myGeneration == globalGeneration) publishOverlaysLocked()
        }
      } finally {
        releaseFrameHandle(handle)
      }
    }
  }

  /** Mutex must be held. */
  private fun bestTrackMatch(
    box: DetectedTextBox,
    claimed: Set<Long>,
    posTolPx: Float,
  ): TrackEntry? {
    val boxCx = (box.rect.left.toFloat() + box.rect.right.toFloat()) * 0.5f
    val boxCy = (box.rect.top.toFloat() + box.rect.bottom.toFloat()) * 0.5f
    val boxW = (box.rect.right.toFloat() - box.rect.left.toFloat()).coerceAtLeast(1f)
    val boxH = (box.rect.bottom.toFloat() - box.rect.top.toFloat()).coerceAtLeast(1f)
    var best: TrackEntry? = null
    var bestDist = Float.MAX_VALUE
    for (entry in tracks) {
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

  /** Mutex must be held. Applies per-track similarity from the regional motion
   *  estimator when valid; falls back to the global translation otherwise. */
  private fun applyMotionLocked(
    update: uniffi.bindings.LiveMotionUpdate?,
    trackSnapshot: List<Pair<Long, NativeRect>>,
    frameWidth: Int,
    frameHeight: Int,
  ) {
    if (update == null) return
    val global = update.global
    if (!global.valid) return
    val regionByTrackId: Map<Long, uniffi.bindings.LiveRegionMotion> =
      trackSnapshot
        .mapIndexedNotNull { idx, (id, _) ->
          val region = update.regions.getOrNull(idx) ?: return@mapIndexedNotNull null
          if (region.valid) id to region else null
        }.toMap()

    for (track in tracks) {
      val region = regionByTrackId[track.id]
      if (region != null) {
        track.orientedBox = applyAffineToOrientedRect(track.orientedBox, region)
        track.tightBox = applyAffineToOrientedRect(track.tightBox, region)
        track.rect = applyAffineToRect(track.rect, region, frameWidth, frameHeight)
      } else {
        track.orientedBox = translatedOrientedRect(track.orientedBox, global.dx, global.dy)
        track.tightBox = translatedOrientedRect(track.tightBox, global.dx, global.dy)
        track.rect = translatedRect(track.rect, global.dx, global.dy, frameWidth, frameHeight)
      }
      track.frameWidth = frameWidth
      track.frameHeight = frameHeight
    }
  }

  /** Mutex must be held. */
  private fun pruneTracksLocked(currentFrame: Long) {
    tracks.removeAll {
      it.sourceText == null &&
        !it.recognitionPending &&
        currentFrame - it.lastVisualFrame > MAX_UNTRACKED_FRAMES
    }
  }

  private fun offsetDetectedBox(
    box: DetectedTextBox,
    offsetX: Int,
    offsetY: Int,
  ): DetectedTextBox {
    val rect =
      NativeRect(
        left = (box.rect.left.toLong() + offsetX).coerceAtLeast(0).toUInt(),
        top = (box.rect.top.toLong() + offsetY).coerceAtLeast(0).toUInt(),
        right = (box.rect.right.toLong() + offsetX).coerceAtLeast(0).toUInt(),
        bottom = (box.rect.bottom.toLong() + offsetY).coerceAtLeast(0).toUInt(),
      )
    val oriented =
      OrientedRect(
        cx = box.orientedBox.cx + offsetX,
        cy = box.orientedBox.cy + offsetY,
        width = box.orientedBox.width,
        height = box.orientedBox.height,
        angleRadians = box.orientedBox.angleRadians,
      )
    val tight =
      OrientedRect(
        cx = box.tightBox.cx + offsetX,
        cy = box.tightBox.cy + offsetY,
        width = box.tightBox.width,
        height = box.tightBox.height,
        angleRadians = box.tightBox.angleRadians,
      )
    return DetectedTextBox(rect, oriented, tight, box.contour, box.score)
  }

  private fun correctTrackGeometry(
    track: TrackEntry,
    observed: DetectedTextBox,
    alpha: Float,
    frameWidth: Int,
    frameHeight: Int,
  ) {
    val keep = 1f - alpha
    val angle = track.orientedBox.angleRadians + normalizedAngleDelta(track.orientedBox.angleRadians, observed.orientedBox.angleRadians) * alpha
    track.orientedBox =
      OrientedRect(
        cx = track.orientedBox.cx * keep + observed.orientedBox.cx * alpha,
        cy = track.orientedBox.cy * keep + observed.orientedBox.cy * alpha,
        width = track.orientedBox.width * keep + observed.orientedBox.width * alpha,
        height = track.orientedBox.height * keep + observed.orientedBox.height * alpha,
        angleRadians = angle,
      )
    track.tightBox =
      OrientedRect(
        cx = track.tightBox.cx * keep + observed.tightBox.cx * alpha,
        cy = track.tightBox.cy * keep + observed.tightBox.cy * alpha,
        width = track.tightBox.width * keep + observed.tightBox.width * alpha,
        height = track.tightBox.height * keep + observed.tightBox.height * alpha,
        angleRadians = angle,
      )
    track.rect =
      clampedRect(
        left = blendEdge(track.rect.left, observed.rect.left, alpha),
        top = blendEdge(track.rect.top, observed.rect.top, alpha),
        right = blendEdge(track.rect.right, observed.rect.right, alpha),
        bottom = blendEdge(track.rect.bottom, observed.rect.bottom, alpha),
        frameWidth = frameWidth,
        frameHeight = frameHeight,
      )
  }

  /** Mutex must be held. Collapses already-recognised entries whose source text
   *  is identical (after normalisation) to `entry`'s, and whose
   *  centre is within `DEDUP_CENTRE_FRAC_OF_BOX_SIZE` of the entry's box size. */
  private fun dedupAgainstEntry(entry: TrackEntry) {
    val source = entry.sourceText ?: return
    val normalisedNew = normaliseForDedup(source)
    val ecx = entry.orientedBox.cx
    val ecy = entry.orientedBox.cy
    val maxBoxSize = max(entry.orientedBox.width, entry.orientedBox.height)
    val tol = maxBoxSize * DEDUP_CENTRE_FRAC_OF_BOX_SIZE
    val victims =
      tracks.filter { other ->
        if (other.id == entry.id) return@filter false
        val otherText = other.sourceText ?: return@filter false
        if (normaliseForDedup(otherText) != normalisedNew) return@filter false
        val dx = abs(other.orientedBox.cx - ecx)
        val dy = abs(other.orientedBox.cy - ecy)
        dx <= tol && dy <= tol
      }
    if (victims.isNotEmpty()) {
      stats.dedupEvictions += victims.size
      tracks.removeAll(victims)
    }
  }

  /** Mutex must be held. */
  private fun publishOverlaysLocked() {
    val toCode = activeTargetCode
    if (toCode == null) {
      _overlays.value = emptyList()
      return
    }
    val items = mutableListOf<LiveOverlayItem>()
    for (group in buildLiveGroupsLocked()) {
      val translated = translationCache[TranslationKey(group.sourceCode, toCode, group.sourceText)] ?: continue
      for (entry in group.tracks) {
        items +=
          LiveOverlayItem(
            groupId = group.id,
            cx = entry.orientedBox.cx,
            cy = entry.orientedBox.cy,
            width = entry.orientedBox.width,
            height = entry.orientedBox.height,
            angleRadians = entry.orientedBox.angleRadians,
            sourceText = entry.sourceText.orEmpty(),
            translatedText = translated,
            groupText = translated,
            frameWidth = entry.frameWidth,
            frameHeight = entry.frameHeight,
          )
      }
    }
    _overlays.value = items
  }

  /** Mutex must be held. */
  private fun buildLiveGroupsLocked(): List<LiveRenderGroup> {
    val renderable = tracks.filter { isRenderableTrack(it) }
    if (renderable.isEmpty()) return emptyList()
    val byId = renderable.associateBy { it.id }
    val inputs =
      renderable.map { entry ->
        uniffi.bindings.LiveTextLineInput(
          trackId = entry.id.toULong(),
          rect = entry.rect,
          orientedBox = entry.orientedBox,
          tightBox = entry.tightBox,
        )
      }
    val groups =
      try {
        uniffi.bindings.groupLiveTextLines(inputs)
      } catch (e: Throwable) {
        Log.w(TAG, "live grouping failed", e)
        return renderable.mapNotNull { entry ->
          val source = entry.sourceText.orEmpty().trim()
          val sourceCode = entry.sourceCode ?: return@mapNotNull null
          LiveRenderGroup(
            id = entry.id.toString(),
            tracks = listOf(entry),
            sourceText = source,
            sourceCode = sourceCode,
          )
        }
      }
    return groups.mapNotNull { group ->
      val groupTracks = group.trackIds.mapNotNull { byId[it.toLong()] }
      if (groupTracks.isEmpty()) return@mapNotNull null
      val source = groupTracks.joinToString(" ") { it.sourceText.orEmpty().trim() }.trim()
      if (source.isEmpty()) return@mapNotNull null
      val sourceCode =
        groupTracks
          .mapNotNull { it.sourceCode }
          .groupingBy { it }
          .eachCount()
          .maxByOrNull { it.value }
          ?.key ?: return@mapNotNull null
      LiveRenderGroup(
        id = groupTracks.joinToString(separator = "-") { it.id.toString() },
        tracks = groupTracks,
        sourceText = source,
        sourceCode = sourceCode,
      )
    }
  }

  /** Mutex must be held. */
  private fun collectMissingGroupTranslationsLocked(to: Language): List<GroupTranslationRequest> {
    val requests = mutableListOf<GroupTranslationRequest>()
    for (group in buildLiveGroupsLocked()) {
      val from = catalog.languageByCode(group.sourceCode) ?: continue
      val key = TranslationKey(group.sourceCode, to.code, group.sourceText)
      if (translationCache.containsKey(key) || key in pendingGroupTranslations) continue
      pendingGroupTranslations.add(key)
      requests += GroupTranslationRequest(key, from, to)
    }
    return requests
  }

  private fun translateGroupRequests(requests: List<GroupTranslationRequest>) {
    if (requests.isEmpty()) return
    workerScope.launch(Dispatchers.Default) {
      var changed = false
      for (request in requests) {
        val key = request.key
        val translated =
          try {
            catalog.translateText(request.from, request.to, key.text)
          } catch (e: Exception) {
            Log.w(TAG, "translate failed for '${key.text}'", e)
            null
          }
        mutex.withLock {
          pendingGroupTranslations.remove(key)
          if (!translated.isNullOrBlank()) {
            if (translationCache.size >= MAX_TRANSLATION_CACHE) {
              translationCache.keys.firstOrNull()?.let(translationCache::remove)
            }
            translationCache[key] = translated
            changed = true
          }
        }
      }
      if (changed) {
        mutex.withLock { publishOverlaysLocked() }
      }
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

  companion object {
    private const val MAX_TRANSLATION_CACHE = 256
    private const val STATS_LOG_INTERVAL_NS: Long = 5_000_000_000L
  }
}

private val WHITESPACE = Regex("\\s+")

private fun normaliseForDedup(text: String): String = text.lowercase().replace(WHITESPACE, " ").trim()

private fun isRenderableTrack(track: TrackEntry): Boolean {
  val source = track.sourceText.orEmpty().trim()
  return source.isNotBlank()
}

private fun translatedOrientedRect(
  rect: OrientedRect,
  dx: Float,
  dy: Float,
): OrientedRect =
  OrientedRect(
    cx = rect.cx + dx,
    cy = rect.cy + dy,
    width = rect.width,
    height = rect.height,
    angleRadians = rect.angleRadians,
  )

private fun applyAffinePoint(
  region: uniffi.bindings.LiveRegionMotion,
  x: Float,
  y: Float,
): Pair<Float, Float> {
  val nx = region.a * x + region.b * y + region.c
  val ny = region.d * x + region.e * y + region.f
  return nx to ny
}

private fun applyAffineToOrientedRect(
  rect: OrientedRect,
  region: uniffi.bindings.LiveRegionMotion,
): OrientedRect {
  val cos = kotlin.math.cos(rect.angleRadians)
  val sin = kotlin.math.sin(rect.angleRadians)
  val hw = rect.width / 2f
  val hh = rect.height / 2f
  val tlx = rect.cx + (-hw) * cos - (-hh) * sin
  val tly = rect.cy + (-hw) * sin + (-hh) * cos
  val trx = rect.cx + (hw) * cos - (-hh) * sin
  val tryY = rect.cy + (hw) * sin + (-hh) * cos
  val brx = rect.cx + (hw) * cos - (hh) * sin
  val bry = rect.cy + (hw) * sin + (hh) * cos
  val blx = rect.cx + (-hw) * cos - (hh) * sin
  val bly = rect.cy + (-hw) * sin + (hh) * cos
  val (tlx2, tly2) = applyAffinePoint(region, tlx, tly)
  val (trx2, try2) = applyAffinePoint(region, trx, tryY)
  val (brx2, bry2) = applyAffinePoint(region, brx, bry)
  val (blx2, bly2) = applyAffinePoint(region, blx, bly)
  val ncx = (tlx2 + trx2 + brx2 + blx2) / 4f
  val ncy = (tly2 + try2 + bry2 + bly2) / 4f
  val topDx = trx2 - tlx2
  val topDy = try2 - tly2
  val botDx = brx2 - blx2
  val botDy = bry2 - bly2
  val newW =
    (
      kotlin.math.sqrt(topDx * topDx + topDy * topDy) +
        kotlin.math.sqrt(botDx * botDx + botDy * botDy)
    ) / 2f
  val leftDx = blx2 - tlx2
  val leftDy = bly2 - tly2
  val rightDx = brx2 - trx2
  val rightDy = bry2 - try2
  val newH =
    (
      kotlin.math.sqrt(leftDx * leftDx + leftDy * leftDy) +
        kotlin.math.sqrt(rightDx * rightDx + rightDy * rightDy)
    ) / 2f
  val topAngle = kotlin.math.atan2(topDy, topDx)
  val botAngle = kotlin.math.atan2(botDy, botDx)
  val newAngle = (topAngle + botAngle) / 2f
  return OrientedRect(
    cx = ncx,
    cy = ncy,
    width = newW.coerceAtLeast(1f),
    height = newH.coerceAtLeast(1f),
    angleRadians = newAngle,
  )
}

private fun applyAffineToRect(
  rect: NativeRect,
  region: uniffi.bindings.LiveRegionMotion,
  frameWidth: Int,
  frameHeight: Int,
): NativeRect {
  val l = rect.left.toFloat()
  val t = rect.top.toFloat()
  val r = rect.right.toFloat()
  val b = rect.bottom.toFloat()
  val (x0, y0) = applyAffinePoint(region, l, t)
  val (x1, y1) = applyAffinePoint(region, r, t)
  val (x2, y2) = applyAffinePoint(region, r, b)
  val (x3, y3) = applyAffinePoint(region, l, b)
  val minX = minOf(x0, x1, x2, x3)
  val maxX = maxOf(x0, x1, x2, x3)
  val minY = minOf(y0, y1, y2, y3)
  val maxY = maxOf(y0, y1, y2, y3)
  return clampedRect(
    left = minX.roundToInt(),
    top = minY.roundToInt(),
    right = maxX.roundToInt(),
    bottom = maxY.roundToInt(),
    frameWidth = frameWidth,
    frameHeight = frameHeight,
  )
}

private fun translatedRect(
  rect: NativeRect,
  dx: Float,
  dy: Float,
  frameWidth: Int,
  frameHeight: Int,
): NativeRect {
  return clampedRect(
    left = (rect.left.toInt() + dx).roundToInt(),
    top = (rect.top.toInt() + dy).roundToInt(),
    right = (rect.right.toInt() + dx).roundToInt(),
    bottom = (rect.bottom.toInt() + dy).roundToInt(),
    frameWidth = frameWidth,
    frameHeight = frameHeight,
  )
}

private fun clampedRect(
  left: Int,
  top: Int,
  right: Int,
  bottom: Int,
  frameWidth: Int,
  frameHeight: Int,
): NativeRect {
  val maxX = frameWidth.coerceAtLeast(1)
  val maxY = frameHeight.coerceAtLeast(1)
  val l = left.coerceIn(0, maxX - 1)
  val t = top.coerceIn(0, maxY - 1)
  val r = right.coerceIn(l + 1, maxX)
  val b = bottom.coerceIn(t + 1, maxY)
  return NativeRect(
    left = l.toUInt(),
    top = t.toUInt(),
    right = r.toUInt(),
    bottom = b.toUInt(),
  )
}

private fun blendEdge(
  current: UInt,
  observed: UInt,
  alpha: Float,
): Int = (current.toFloat() * (1f - alpha) + observed.toFloat() * alpha).roundToInt()

private fun normalizedAngleDelta(
  current: Float,
  observed: Float,
): Float {
  var delta = observed - current
  val pi = kotlin.math.PI.toFloat()
  while (delta > pi) delta -= 2f * pi
  while (delta < -pi) delta += 2f * pi
  return delta
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
