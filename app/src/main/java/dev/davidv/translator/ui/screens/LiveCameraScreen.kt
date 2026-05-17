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

package dev.davidv.translator.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.davidv.translator.CameraIntrinsicsRaw
import dev.davidv.translator.DebugContourFrame
import dev.davidv.translator.ImuService
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageAvailabilityState
import dev.davidv.translator.LanguageMetadata
import dev.davidv.translator.LiveFrameJni
import dev.davidv.translator.LivePlanarOcrEngine
import dev.davidv.translator.R
import dev.davidv.translator.TranslatorMessage
import dev.davidv.translator.cameraIntrinsicsFromCameraX
import dev.davidv.translator.ui.components.LanguageSelector
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.max
import android.util.Size as AndroidSize

private const val TAG = "LiveCameraScreen"
private val TARGET_RESOLUTION = AndroidSize(1080, 1920)
private val ANALYZER_RESOLUTION = AndroidSize(1080, 1920)

/** Phase 1 IMU smoke-test toggle. When true, locks the gyro baseline at first
 *  composition and renders a crosshair at the projected position of the
 *  initially-screen-center world point. If the gyro integration and intrinsics
 *  are right, the crosshair stays glued to that world point under pure
 *  rotation. Flip and rebuild — no UI toggle. */
private const val DEBUG_IMU_CROSSHAIR: Boolean = true

/** Debug overlay that draws the raw detector contour polygons (PaddleOCR DB
 *  mask output) as cyan outlines, updated each detection cycle (~5 Hz).
 *  Diagnostic: confirms whether the detector itself produces stable
 *  contours across firings vs whether wobble is fully on the tracker side. */
private const val DEBUG_DRAW_DETECTOR_CONTOUR: Boolean = true

/** Suppress the live-overlay layer (labelled tracker boxes / translated text)
 *  while diagnosing other overlays. Lets the cyan detector contour show
 *  through without clutter. */
private const val DEBUG_HIDE_TRACKER_OVERLAY: Boolean = false

/** Show a small overlay pill in the corner reporting tracker state
 *  (Idle/Acquiring/Locked/Lost + inliers + last acquire's det/rec
 *  counts). Useful while tuning, off in production. */
private const val DEBUG_SHOW_TRACKER_STATUS: Boolean = true

private data class FrameInfo(
  val displayWidth: Int,
  val displayHeight: Int,
  val rotationDegrees: Int,
)

@Composable
fun LiveCameraScreen(
  from: Language,
  to: Language,
  isAutoSource: Boolean,
  canSwap: Boolean,
  languageState: LanguageAvailabilityState,
  languageMetadata: Map<Language, LanguageMetadata>,
  onMessage: (TranslatorMessage) -> Unit,
  liveOverlayDefaultEnabled: Boolean,
  catalog: dev.davidv.translator.LanguageCatalog?,
  onClose: () -> Unit,
) {
  val context = LocalContext.current
  var permissionGranted by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED,
    )
  }
  var hasAsked by remember { mutableStateOf(permissionGranted) }
  val requestPermission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      permissionGranted = granted
      hasAsked = true
    }

  LaunchedEffect(Unit) {
    if (!permissionGranted && !hasAsked) {
      requestPermission.launch(Manifest.permission.CAMERA)
    }
  }

  BackHandler { onClose() }

  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .background(Color.Black),
  ) {
    when {
      permissionGranted ->
        CameraSurface(
          from = from,
          to = to,
          isAutoSource = isAutoSource,
          canSwap = canSwap,
          languageState = languageState,
          languageMetadata = languageMetadata,
          onMessage = onMessage,
          liveOverlayDefaultEnabled = liveOverlayDefaultEnabled,
          catalog = catalog,
          onClose = onClose,
        )
      hasAsked ->
        PermissionPrompt(
          onRequest = { requestPermission.launch(Manifest.permission.CAMERA) },
          onCancel = onClose,
        )
    }
  }
}

@Composable
private fun PermissionPrompt(
  onRequest: () -> Unit,
  onCancel: () -> Unit,
) {
  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = "Camera permission is needed for live translate.",
      color = Color.White,
    )
    Spacer(modifier = Modifier.size(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(
        text = "Grant",
        color = Color.White,
        modifier =
          Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.2f))
            .clickable { onRequest() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
      )
      Text(
        text = "Cancel",
        color = Color.White,
        modifier =
          Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.1f))
            .clickable { onCancel() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
      )
    }
  }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun CameraSurface(
  from: Language,
  to: Language,
  isAutoSource: Boolean,
  canSwap: Boolean,
  languageState: LanguageAvailabilityState,
  languageMetadata: Map<Language, LanguageMetadata>,
  onMessage: (TranslatorMessage) -> Unit,
  liveOverlayDefaultEnabled: Boolean,
  catalog: dev.davidv.translator.LanguageCatalog?,
  onClose: () -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val mainExecutor: Executor = remember { ContextCompat.getMainExecutor(context) }

  var torchOn by remember { mutableStateOf(false) }
  val hasPaddleOcrModels =
    remember(catalog, languageState, from.code, isAutoSource) {
      if (isAutoSource) {
        catalog != null &&
          languageState.allLanguages().any { language ->
            catalog.installedOcrEngines(language.code).contains("ppocr")
          }
      } else {
        catalog?.installedOcrEngines(from.code)?.contains("ppocr") == true
      }
    }
  var liveOverlayOn by remember { mutableStateOf(liveOverlayDefaultEnabled && hasPaddleOcrModels) }
  var camera by remember { mutableStateOf<Camera?>(null) }
  val cameraControl = camera?.cameraControl
  var hasFlashUnit by remember { mutableStateOf(false) }
  var isCapturing by remember { mutableStateOf(false) }
  var focusPoint by remember { mutableStateOf<Offset?>(null) }
  val focusScale = remember { Animatable(1.5f) }
  val focusAlpha = remember { Animatable(0f) }
  // Persistent crop centre for the live OCR engine. Defaults to dead centre; a tap
  // moves it so we crop around the user's focus area instead of the middle of the frame.
  var cropFocusNormalized by remember { mutableStateOf(Offset(0.5f, 0.5f)) }

  LaunchedEffect(focusPoint) {
    val point = focusPoint ?: return@LaunchedEffect
    focusScale.snapTo(1.5f)
    focusAlpha.snapTo(1f)
    focusScale.animateTo(1f, tween(durationMillis = 280))
    focusAlpha.animateTo(0f, tween(durationMillis = 420))
    if (focusPoint == point) focusPoint = null
  }

  val imageCapture =
    remember {
      val resolutionSelector =
        ResolutionSelector.Builder()
          .setResolutionStrategy(
            ResolutionStrategy(
              TARGET_RESOLUTION,
              ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
            ),
          )
          .build()
      ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setResolutionSelector(resolutionSelector)
        .build()
    }

  val imageAnalysis =
    remember {
      // Cap analyzer source at ~1.5 MP regardless of device. The bigger the source,
      // the slower the per-frame bitmap conversion (RGBA copy + rotation) — and
      // detection downscales to a fixed 400k target anyway, so the larger source
      // mostly burns memory bandwidth.
      val maxPixels = 1_500_000L
      val resolutionSelector =
        ResolutionSelector.Builder()
          .setResolutionStrategy(
            ResolutionStrategy(
              ANALYZER_RESOLUTION,
              ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
            ),
          )
          .setResolutionFilter { sizes, _ ->
            sizes.filter { it.width.toLong() * it.height <= maxPixels }
          }
          .build()
      ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .setResolutionSelector(resolutionSelector)
        .build()
    }
  val analyzerExecutor =
    remember { java.util.concurrent.Executors.newSingleThreadExecutor() }

  val imuService = remember { ImuService(context) }
  DisposableEffect(imuService) {
    imuService.start()
    onDispose { imuService.stop() }
  }
  var cameraIntrinsics by remember { mutableStateOf<CameraIntrinsicsRaw?>(null) }
  var latestFrameInfo by remember { mutableStateOf<FrameInfo?>(null) }

  val workerScope = androidx.compose.runtime.rememberCoroutineScope()
  val liveOcrEngine: LivePlanarOcrEngine? =
    remember(catalog) { catalog?.let { LivePlanarOcrEngine(it, workerScope, imuService) } }
  val debugContours by (liveOcrEngine?.debugContours ?: remember { kotlinx.coroutines.flow.MutableStateFlow<DebugContourFrame?>(null) })
    .collectAsState()
  var previewSizePx by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

  DisposableEffect(Unit) {
    onDispose { analyzerExecutor.shutdown() }
  }

  val analyzerSession = remember { java.util.concurrent.atomic.AtomicLong(0L) }
  LaunchedEffect(from.code, isAutoSource, hasPaddleOcrModels, liveOverlayDefaultEnabled) {
    liveOverlayOn = liveOverlayDefaultEnabled && hasPaddleOcrModels
    if (!liveOverlayOn) liveOcrEngine?.clear()
  }

  DisposableEffect(liveOverlayOn, liveOcrEngine, from.code, to.code, isAutoSource) {
    val engine = liveOcrEngine
    val mySession = analyzerSession.incrementAndGet()
    if (liveOverlayOn && engine != null) {
      imageAnalysis.setAnalyzer(analyzerExecutor) { proxy ->
        val tConvert = System.nanoTime()
        val handle = engine.acquireFrameHandle()
        if (handle == null) {
          proxy.close()
          return@setAnalyzer
        }
        val plane = proxy.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = proxy.width
        val height = proxy.height
        val rotation = proxy.imageInfo.rotationDegrees
        // Capture the sensor exposure timestamp BEFORE proxy.close()
        // since accessing imageInfo on a closed proxy isn't guaranteed.
        // Same clock as SensorEvent.timestamp (elapsedRealtimeNanos), so
        // it composes with imuService.rotationAt() below.
        val captureTs = proxy.imageInfo.timestamp
        val length = width * pixelStride * height
        val ok =
          if (rowStride == width * pixelStride) {
            // Fast path: contiguous DirectByteBuffer → straight memcpy into the
            // Rust-side buffer via JNI. Zero JVM-side allocation.
            plane.buffer.rewind()
            LiveFrameJni.writeFrom(
              handle.rawAddressForJni().toLong(),
              plane.buffer,
              length,
              width,
              height,
              rotation,
            )
          } else {
            // Stride padding — rare for RGBA_8888. Repack row-by-row into a
            // temp ByteArray and use the uniffi marshalling fallback.
            val src = ByteArray(plane.buffer.remaining())
            plane.buffer.rewind()
            plane.buffer.get(src)
            val packed = ByteArray(length)
            val rowBytes = width * pixelStride
            for (row in 0 until height) {
              System.arraycopy(src, row * rowStride, packed, row * rowBytes, rowBytes)
            }
            handle.resetViaUniffi(packed, width.toUInt(), height.toUInt(), rotation)
            true
          }
        proxy.close()
        val convertMs = (System.nanoTime() - tConvert) / 1_000_000.0
        if (!ok || analyzerSession.get() != mySession) {
          engine.releaseFrameHandle(handle)
          return@setAnalyzer
        }
        if (DEBUG_IMU_CROSSHAIR) {
          val dispW = if (rotation == 90 || rotation == 270) height else width
          val dispH = if (rotation == 90 || rotation == 270) width else height
          latestFrameInfo = FrameInfo(dispW, dispH, rotation)
        }
        // Sample R_prev at the camera's actual capture timestamp (same
        // clock as SensorEvent.timestamp) rather than at this analyzer
        // callback. Without this we'd miss the gyro rotation that
        // happened between sensor exposure and our callback firing —
        // typically 30-60 ms of pipeline latency — which under sustained
        // pan shows up as a constant overlay offset proportional to ω.
        // Falls back to the un-timestamped "now" snapshot when history
        // doesn't yet cover that moment (first few frames).
        val imuSnap = imuService.rotationAt(captureTs) ?: imuService.currentRotation()
        val fx = cropFocusNormalized.x
        val fy = cropFocusNormalized.y
        engine.submitFrame(handle, width, height, rotation, fx, fy, from, to, isAutoSource, convertMs, imuSnap)
      }
    } else {
      imageAnalysis.clearAnalyzer()
      engine?.clear()
    }
    onDispose {
      analyzerSession.incrementAndGet()
      imageAnalysis.clearAnalyzer()
      engine?.clear()
    }
  }

  val previewView =
    remember {
      PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        // PERFORMANCE = SurfaceView under the hood, one fewer buffer hop
        // than COMPATIBLE (TextureView). Lower preview latency so the
        // IMU-extrapolated overlay doesn't lead the pixels.
        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
      }
    }

  DisposableEffect(previewView) {
    val scaleDetector =
      ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
          override fun onScale(detector: ScaleGestureDetector): Boolean {
            val cam = camera ?: return true
            val zoomState = cam.cameraInfo.zoomState.value ?: return true
            val target =
              (zoomState.zoomRatio * detector.scaleFactor)
                .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
            cam.cameraControl.setZoomRatio(target)
            return true
          }
        },
      )
    val tapDetector =
      GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
          override fun onSingleTapUp(e: MotionEvent): Boolean {
            focusPoint = Offset(e.x, e.y)
            val viewW = previewView.width.toFloat().coerceAtLeast(1f)
            val viewH = previewView.height.toFloat().coerceAtLeast(1f)
            cropFocusNormalized =
              Offset(
                (e.x / viewW).coerceIn(0f, 1f),
                (e.y / viewH).coerceIn(0f, 1f),
              )
            val cam = camera ?: return true
            val point = previewView.meteringPointFactory.createPoint(e.x, e.y)
            val action =
              FocusMeteringAction
                .Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .build()
            runCatching { cam.cameraControl.startFocusAndMetering(action) }
              .onFailure { Log.w(TAG, "focus action failed", it) }
            return true
          }
        },
      )
    previewView.setOnTouchListener { _, event ->
      scaleDetector.onTouchEvent(event)
      tapDetector.onTouchEvent(event)
      true
    }
    onDispose { previewView.setOnTouchListener(null) }
  }

  DisposableEffect(lifecycleOwner) {
    val providerFuture = ProcessCameraProvider.getInstance(context)
    providerFuture.addListener({
      val provider = providerFuture.get()
      val resolutionSelector =
        ResolutionSelector.Builder()
          .setResolutionStrategy(
            ResolutionStrategy(
              TARGET_RESOLUTION,
              ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
            ),
          )
          .build()
      val preview =
        Preview.Builder()
          .setResolutionSelector(resolutionSelector)
          .build()
          .also { it.setSurfaceProvider(previewView.surfaceProvider) }

      try {
        provider.unbindAll()
        val boundCamera =
          provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageCapture,
            imageAnalysis,
          )
        camera = boundCamera
        hasFlashUnit = boundCamera.cameraInfo.hasFlashUnit()
        cameraIntrinsics = cameraIntrinsicsFromCameraX(boundCamera.cameraInfo)
        liveOcrEngine?.setCameraIntrinsics(cameraIntrinsics)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to bind camera", e)
      }
    }, mainExecutor)

    onDispose {
      runCatching { providerFuture.get().unbindAll() }
    }
  }

  LaunchedEffect(torchOn, cameraControl) {
    cameraControl?.enableTorch(torchOn && hasFlashUnit)
  }

  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .onSizeChanged { previewSizePx = it },
  ) {
    AndroidView(
      factory = { previewView },
      modifier = Modifier.fillMaxSize(),
    )

    // Phase 4 of FUTURE_BITMAP_OVERLAY.md: only the bitmap-overlay
    // path. The previous Compose `LiveOverlayLayer` was per-overlay
    // Compose layout (~30 boxes × 30fps → main thread saturation);
    // it's gone. This single AndroidView warps the canonical bitmap
    // via Matrix.setPolyToPoly — one draw per frame, no Compose
    // recomposition.
    if (liveOverlayOn) {
      val bitmapFrame by
        (liveOcrEngine?.bitmapOverlay ?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) })
          .collectAsState()
      AndroidView(
        factory = { ctx ->
          dev.davidv.translator.ui.components.PlanarBitmapOverlayView(ctx).apply {
            // Phase 5: per-render-frame IMU extrapolation. View
            // queries `currentRotation()` on each vsync and predicts
            // the new H from the camera-frame H + IMU delta. Smooths
            // overlay motion between 30 Hz camera frames.
            setImuService(imuService)
          }
        },
        modifier = Modifier.fillMaxSize(),
        update = { view -> view.update(bitmapFrame) },
      )
    }

    if (DEBUG_IMU_CROSSHAIR) {
      ImuCrosshairOverlay(
        imuService = imuService,
        intrinsics = cameraIntrinsics,
        frameInfo = latestFrameInfo,
        previewSizePx = previewSizePx,
      )
    }

    if (DEBUG_DRAW_DETECTOR_CONTOUR) {
      DebugContourOverlay(
        frame = debugContours,
        previewSizePx = previewSizePx,
      )
    }

    if (DEBUG_SHOW_TRACKER_STATUS && liveOcrEngine != null) {
      val status by liveOcrEngine.trackerStatus.collectAsState()
      TrackerStatusPill(status)
    }

    val indicatorSizeDp = 72.dp
    val indicatorRadiusPx =
      with(androidx.compose.ui.platform.LocalDensity.current) {
        (indicatorSizeDp / 2).toPx()
      }
    focusPoint?.let { pt ->
      Box(
        modifier =
          Modifier
            .offset {
              IntOffset(
                (pt.x - indicatorRadiusPx).toInt(),
                (pt.y - indicatorRadiusPx).toInt(),
              )
            }
            .size(indicatorSizeDp)
            .scale(focusScale.value)
            .alpha(focusAlpha.value)
            .border(2.dp, Color.White, CircleShape),
      )
    }

    TopLanguagePills(
      from = from,
      to = to,
      isAutoSource = isAutoSource,
      canSwap = canSwap,
      languageState = languageState,
      languageMetadata = languageMetadata,
      onMessage = onMessage,
      modifier =
        Modifier
          .fillMaxWidth()
          .statusBarsPadding()
          .padding(horizontal = 8.dp, vertical = 4.dp),
    )

    BottomControls(
      torchOn = torchOn,
      hasFlashUnit = hasFlashUnit,
      onTorchToggle = { torchOn = !torchOn },
      liveOverlayOn = liveOverlayOn,
      liveOverlayAvailable = hasPaddleOcrModels,
      onLiveOverlayToggle = {
        if (!hasPaddleOcrModels) {
          Toast
            .makeText(
              context,
              "You need to download the OCR models for this language",
              Toast.LENGTH_SHORT,
            )
            .show()
          return@BottomControls
        }
        liveOverlayOn = !liveOverlayOn
        if (!liveOverlayOn) liveOcrEngine?.clear()
      },
      isCapturing = isCapturing,
      onCapture = {
        if (isCapturing) return@BottomControls
        isCapturing = true
        val outputFile = File.createTempFile("camera_capture_", ".jpg", context.cacheDir)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        imageCapture.takePicture(
          outputOptions,
          mainExecutor,
          object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
              val uri =
                FileProvider.getUriForFile(
                  context,
                  "${context.packageName}.fileprovider",
                  outputFile,
                )
              isCapturing = false
              onMessage(TranslatorMessage.SetImageUri(uri = uri, deleteAfterLoad = true))
              onClose()
            }

            override fun onError(exception: ImageCaptureException) {
              Log.e(TAG, "Image capture failed", exception)
              outputFile.delete()
              isCapturing = false
            }
          },
        )
      },
      modifier =
        Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth(),
    )
  }
}

@Composable
private fun TopLanguagePills(
  from: Language,
  to: Language,
  isAutoSource: Boolean,
  canSwap: Boolean,
  languageState: LanguageAvailabilityState,
  languageMetadata: Map<Language, LanguageMetadata>,
  onMessage: (TranslatorMessage) -> Unit,
  modifier: Modifier = Modifier,
) {
  val fromLanguages =
    languageState.allLanguages().filter { x ->
      (isAutoSource || x != from) && (languageState.availabilityFor(x)?.hasToEnglish == true || x.isEnglish)
    }
  val toLanguages =
    languageState.allLanguages().filter { x ->
      x != from && x != to && (languageState.availabilityFor(x)?.hasFromEnglish == true || x.isEnglish)
    }
  val pillShape = RoundedCornerShape(50)
  val pillBackground = Color.Black.copy(alpha = 0.5f)

  val pillWidth = 110.dp
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier =
        Modifier
          .width(pillWidth)
          .clip(pillShape)
          .background(pillBackground),
    ) {
      LanguageSelector(
        selectedLanguage = from,
        availableLanguages = fromLanguages,
        languageMetadata = languageMetadata,
        onLanguageSelected = { onMessage(TranslatorMessage.FromLang(it)) },
        isAutoSource = isAutoSource,
        showAutoOption = true,
        onAutoSelected = { onMessage(TranslatorMessage.EnableAutoSource) },
        textColor = Color.White,
        marquee = false,
      )
    }
    CompositionLocalProvider(LocalContentColor provides Color.White) {
      IconButton(
        onClick = { onMessage(TranslatorMessage.SwapLanguages) },
        enabled = canSwap,
        modifier = Modifier.size(32.dp),
      ) {
        Icon(
          painter = painterResource(id = R.drawable.compare),
          contentDescription = "Reverse translation direction",
        )
      }
    }
    Box(
      modifier =
        Modifier
          .width(pillWidth)
          .clip(pillShape)
          .background(pillBackground),
    ) {
      LanguageSelector(
        selectedLanguage = to,
        availableLanguages = toLanguages,
        languageMetadata = languageMetadata,
        onLanguageSelected = { onMessage(TranslatorMessage.ToLang(it)) },
        textColor = Color.White,
        marquee = false,
      )
    }
  }
}

@Composable
private fun BottomControls(
  torchOn: Boolean,
  hasFlashUnit: Boolean,
  onTorchToggle: () -> Unit,
  liveOverlayOn: Boolean,
  liveOverlayAvailable: Boolean,
  onLiveOverlayToggle: () -> Unit,
  isCapturing: Boolean,
  onCapture: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier =
      modifier
        .padding(horizontal = 24.dp, vertical = 24.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(
      onClick = onTorchToggle,
      enabled = hasFlashUnit,
    ) {
      Icon(
        painter = painterResource(if (torchOn) R.drawable.flash_on else R.drawable.flash_off),
        contentDescription = if (torchOn) "Torch on" else "Torch off",
        tint = if (hasFlashUnit) Color.White else Color.White.copy(alpha = 0.3f),
      )
    }

    Box(
      modifier =
        Modifier
          .size(72.dp)
          .clip(CircleShape)
          .border(4.dp, Color.White, CircleShape)
          .padding(6.dp)
          .clip(CircleShape)
          .background(if (isCapturing) Color.White.copy(alpha = 0.5f) else Color.White)
          .clickable(enabled = !isCapturing, onClick = onCapture),
    )

    IconButton(onClick = onLiveOverlayToggle) {
      Icon(
        painter = painterResource(R.drawable.auto_awesome),
        contentDescription = if (liveOverlayOn) "Live overlay on" else "Live overlay off",
        tint =
          when {
            !liveOverlayAvailable -> Color.White.copy(alpha = 0.25f)
            liveOverlayOn -> Color.White
            else -> Color.White.copy(alpha = 0.4f)
          },
      )
    }
  }
}

private fun mul3x3TransposeLeft(
  a: FloatArray,
  b: FloatArray,
): FloatArray {
  val out = FloatArray(9)
  for (i in 0..2) {
    for (j in 0..2) {
      var s = 0f
      for (k in 0..2) s += a[k * 3 + i] * b[k * 3 + j]
      out[i * 3 + j] = s
    }
  }
  return out
}

private const val RENDER_IMU_LEAD_NS: Long = 12_000_000L

@Composable
private fun ImuCrosshairOverlay(
  imuService: ImuService?,
  intrinsics: CameraIntrinsicsRaw?,
  frameInfo: FrameInfo?,
  previewSizePx: androidx.compose.ui.unit.IntSize,
) {
  if (imuService == null || intrinsics == null || frameInfo == null) return
  if (previewSizePx.width == 0 || previewSizePx.height == 0) return
  val baselineRay = remember { floatArrayOf(0f, 0f, 1f) }
  var baseRotation by remember { mutableStateOf<FloatArray?>(null) }
  LaunchedEffect(imuService) {
    baseRotation = imuService.currentRotation()
  }
  val baseRot = baseRotation ?: return

  val renderTimeNs = remember { mutableLongStateOf(System.nanoTime()) }
  LaunchedEffect(Unit) {
    while (true) {
      androidx.compose.runtime.withFrameNanos { ts ->
        renderTimeNs.longValue = ts
      }
    }
  }

  val frameW = frameInfo.displayWidth
  val frameH = frameInfo.displayHeight
  val pixI = intrinsics.pixelIntrinsics(frameW, frameH, frameInfo.rotationDegrees)
  val previewScale =
    max(
      previewSizePx.width.toFloat() / frameW.toFloat(),
      previewSizePx.height.toFloat() / frameH.toFloat(),
    )
  val displayedW = frameW * previewScale
  val displayedH = frameH * previewScale
  val offX = (displayedW - previewSizePx.width) / 2f
  val offY = (displayedH - previewSizePx.height) / 2f

  val crosshairSizeDp = 28.dp
  val density = LocalDensity.current
  val crosshairRadiusPx = with(density) { (crosshairSizeDp / 2).toPx() }

  var lastLogNs by remember { mutableLongStateOf(0L) }

  Box(modifier = Modifier.fillMaxSize()) {
    Box(
      modifier =
        Modifier
          .offset {
            val nowNs = renderTimeNs.longValue
            val rotDev = imuService.currentRotation(RENDER_IMU_LEAD_NS)
            val dDev = mul3x3TransposeLeft(rotDev, baseRot)
            val dCam = deviceToCameraSandwich(dDev)
            val rNow = rotate(dCam, baselineRay)
            if (nowNs - lastLogNs > 500_000_000L) {
              lastLogNs = nowNs
              Log.d(
                TAG,
                "imu rDev=[${rotDev.joinToString(",") { "%.3f".format(it) }}] " +
                  "rayCam=[${"%.3f".format(rNow[0])},${"%.3f".format(rNow[1])},${"%.3f".format(rNow[2])}] " +
                  "fx=${"%.1f".format(pixI.fx)} fy=${"%.1f".format(pixI.fy)} " +
                  "frame=${frameW}x$frameH rot=${frameInfo.rotationDegrees}",
              )
            }
            if (rNow[2] <= 0.01f) {
              return@offset IntOffset(-9999, -9999)
            }
            val uImg = pixI.fx * rNow[0] / rNow[2] + pixI.cx
            val vImg = pixI.fy * rNow[1] / rNow[2] + pixI.cy
            val uView = uImg * previewScale - offX
            val vView = vImg * previewScale - offY
            IntOffset(
              (uView - crosshairRadiusPx).toInt(),
              (vView - crosshairRadiusPx).toInt(),
            )
          }
          .size(crosshairSizeDp)
          .border(2.dp, Color.Red, CircleShape),
    )
  }
}

/** Rotation conversion device-frame → camera-frame. Device frame: +X right,
 *  +Y up, +Z out of screen (toward user). Camera frame (back camera, OpenCV
 *  convention): +X right, +Y down, +Z into scene. Mapping matrix
 *  `M = diag(1, -1, -1)`; for rotations `R_cam = M * R_dev * M` (M is its
 *  own inverse). Valid when the phone is in natural portrait orientation
 *  and the back camera is selected — sufficient for the Phase 1 smoke test. */
private fun deviceToCameraSandwich(rDev: FloatArray): FloatArray {
  // M * R * M with M = diag(1, -1, -1): flip signs on rows/columns 1 and 2.
  val r = FloatArray(9)
  for (i in 0..2) {
    for (j in 0..2) {
      val sign = (if (i == 0) 1 else -1) * (if (j == 0) 1 else -1)
      r[i * 3 + j] = sign * rDev[i * 3 + j]
    }
  }
  return r
}

@Composable
private fun DebugContourOverlay(
  frame: DebugContourFrame?,
  previewSizePx: androidx.compose.ui.unit.IntSize,
) {
  if (frame == null) return
  if (frame.frameWidth == 0 || frame.frameHeight == 0) return
  if (previewSizePx.width == 0 || previewSizePx.height == 0) return
  val scale =
    max(
      previewSizePx.width.toFloat() / frame.frameWidth.toFloat(),
      previewSizePx.height.toFloat() / frame.frameHeight.toFloat(),
    )
  val offX = (frame.frameWidth * scale - previewSizePx.width) / 2f
  val offY = (frame.frameHeight * scale - previewSizePx.height) / 2f
  androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
    for (poly in frame.contoursDisplay) {
      val n = poly.size / 2
      if (n < 2) continue
      val path = androidx.compose.ui.graphics.Path()
      val x0 = poly[0] * scale - offX
      val y0 = poly[1] * scale - offY
      path.moveTo(x0, y0)
      for (i in 1 until n) {
        val x = poly[i * 2] * scale - offX
        val y = poly[i * 2 + 1] * scale - offY
        path.lineTo(x, y)
      }
      path.close()
      drawPath(
        path = path,
        color = androidx.compose.ui.graphics.Color(0xFFFF00FF),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f),
      )
    }
  }
}

private fun rotate(
  r: FloatArray,
  v: FloatArray,
): FloatArray {
  return floatArrayOf(
    r[0] * v[0] + r[1] * v[1] + r[2] * v[2],
    r[3] * v[0] + r[4] * v[1] + r[5] * v[2],
    r[6] * v[0] + r[7] * v[1] + r[8] * v[2],
  )
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.TrackerStatusPill(status: dev.davidv.translator.TrackerStatus) {
  val (color, label) =
    when (status.state) {
      uniffi.bindings.PlanarTrackerState.IDLE ->
        androidx.compose.ui.graphics.Color(0xCC444444) to "IDLE"
      uniffi.bindings.PlanarTrackerState.ACQUIRING ->
        androidx.compose.ui.graphics.Color(0xCCB58900) to "ACQUIRING"
      uniffi.bindings.PlanarTrackerState.LOCKED ->
        androidx.compose.ui.graphics.Color(0xCC2AA198) to "LOCKED"
      uniffi.bindings.PlanarTrackerState.LOST ->
        androidx.compose.ui.graphics.Color(0xCCDC322F) to "LOST"
    }
  // Format: STATE | anchor#N inliers | det=D rec_ok=K rec_empty=M pending=P
  val detPart =
    if (status.lastAcquireDet < 0) {
      ""
    } else {
      " | det=${status.lastAcquireDet} rec_ok=${status.lastAcquireRecOk} rec_empty=${status.lastAcquireRecEmpty} pending=${status.lastAcquirePending}"
    }
  val anchorPart =
    if (status.anchorId == 0L) {
      ""
    } else {
      " | a#${status.anchorId} inl=${status.inliers}"
    }
  val text = "$label$anchorPart$detPart"
  androidx.compose.foundation.layout.Box(
    modifier =
      androidx.compose.ui.Modifier
        .align(androidx.compose.ui.Alignment.TopStart)
        .padding(top = 56.dp, start = 8.dp)
        .background(color, shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
        .padding(horizontal = 8.dp, vertical = 4.dp),
  ) {
    androidx.compose.material3.Text(
      text = text,
      color = androidx.compose.ui.graphics.Color.White,
      style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
    )
  }
}
