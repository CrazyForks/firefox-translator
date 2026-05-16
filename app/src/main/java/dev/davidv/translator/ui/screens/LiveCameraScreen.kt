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
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageAvailabilityState
import dev.davidv.translator.LanguageMetadata
import dev.davidv.translator.LiveFrameJni
import dev.davidv.translator.LiveOcrEngine
import dev.davidv.translator.LiveOverlayItem
import dev.davidv.translator.R
import dev.davidv.translator.TranslatorMessage
import dev.davidv.translator.ui.components.LanguageSelector
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import android.util.Size as AndroidSize

private const val TAG = "LiveCameraScreen"
private val TARGET_RESOLUTION = AndroidSize(1080, 1920)
private val ANALYZER_RESOLUTION = AndroidSize(1080, 1920)

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

  val workerScope = androidx.compose.runtime.rememberCoroutineScope()
  val liveOcrEngine: LiveOcrEngine? =
    remember(catalog) { catalog?.let { LiveOcrEngine(it, workerScope) } }
  val liveOverlays by (liveOcrEngine?.overlays ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList()) })
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
        val fx = cropFocusNormalized.x
        val fy = cropFocusNormalized.y
        engine.submitFrame(handle, width, height, rotation, fx, fy, from, to, isAutoSource, convertMs)
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
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
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

    LiveOverlayLayer(
      overlays = if (liveOverlayOn) liveOverlays else emptyList(),
      previewSizePx = previewSizePx,
    )

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

@Composable
private fun LiveOverlayLayer(
  overlays: List<LiveOverlayItem>,
  previewSizePx: androidx.compose.ui.unit.IntSize,
) {
  if (overlays.isEmpty() || previewSizePx.width == 0 || previewSizePx.height == 0) return
  val density = LocalDensity.current
  val textMeasurer = rememberTextMeasurer()
  val stableLayouts = remember { mutableStateMapOf<String, StableLayout>() }
  // Display-refresh-rate ticker. Read inside `offset { ... }` lambdas so each
  // overlay's position is re-evaluated during the layout phase on every frame
  // (60-120Hz) — Compose runs the offset lambda when its state reads change,
  // without retriggering composition or text-fitting work.
  val renderTimeNs = remember { mutableLongStateOf(System.nanoTime()) }
  LaunchedEffect(Unit) {
    while (true) {
      androidx.compose.runtime.withFrameNanos { ts ->
        renderTimeNs.longValue = ts
      }
    }
  }
  val placements =
    overlays.map { item ->
      // FILL_CENTER scale from full-frame pixels to PreviewView pixels.
      val scale =
        maxOf(
          previewSizePx.width.toFloat() / item.frameWidth.toFloat(),
          previewSizePx.height.toFloat() / item.frameHeight.toFloat(),
        )
      val displayedW = item.frameWidth * scale
      val displayedH = item.frameHeight * scale
      val offX = (displayedW - previewSizePx.width) / 2f
      val offY = (displayedH - previewSizePx.height) / 2f
      val cxPx = item.cx * scale - offX
      val cyPx = item.cy * scale - offY
      val widthPx = (item.width * scale).coerceAtLeast(1f)
      val heightPx = (item.height * scale).coerceAtLeast(1f)
      val widthDp = with(density) { widthPx.toDp() }
      val heightDp = with(density) { heightPx.toDp() }
      // graphicsLayer rotates around the box centre; we offset by (cx - w/2, cy - h/2).
      val leftPx = (cxPx - widthPx / 2f).toInt()
      val topPx = (cyPx - heightPx / 2f).toInt()
      val angleDeg = item.angleRadians * 180f / kotlin.math.PI.toFloat()
      Placement(
        groupId = item.groupId,
        groupText = item.groupText,
        text = item.translatedText,
        left = leftPx,
        top = topPx,
        widthPx = widthPx,
        heightPx = heightPx,
        widthDp = widthDp,
        heightDp = heightDp,
        angleDeg = angleDeg,
        velocityXPx = item.velocityX * scale,
        velocityYPx = item.velocityY * scale,
        baseTimestampNs = item.baseTimestampNs,
      )
    }
  val groupedPlacements = placements.groupBy { it.groupId }
  stableLayouts.keys.retainAll(groupedPlacementsStableKeys(groupedPlacements))
  val fittedGroups =
    groupedPlacements.mapValues { (groupId, groupPlacements) ->
      val stableKey = stableLayoutKey(groupId, groupPlacements.firstOrNull()?.groupText.orEmpty())
      val previous = stableLayouts[stableKey]
      val fitted =
        fitLiveOverlayGroup(
          placements = groupPlacements,
          textMeasurer = textMeasurer,
          density = density,
          baseStyle = androidx.compose.material3.MaterialTheme.typography.bodySmall,
          previous = previous,
        )
      stableLayouts[stableKey] = fitted.stable
      fitted
    }
  val fittedByPlacement =
    fittedGroups.values
      .flatMap { it.items }
      .associateBy { it.placement }
  val blockGroups = fittedGroups.values.mapNotNull { it.block }
  val perLinePlacements = placements.filter { fittedGroups[it.groupId]?.block == null }

  // Background pass: draw opaque rounded rects inside an offscreen layer composited at
  // alpha=0.7 — overlapping shapes merge into a single union instead of stacking the dim.
  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .graphicsLayer {
          alpha = 0.7f
          compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
        },
  ) {
    for (p in perLinePlacements) {
      Box(
        modifier =
          Modifier
            .offset {
              val dtSec =
                ((renderTimeNs.longValue - p.baseTimestampNs) / 1e9f)
                  .coerceIn(0f, MAX_EXTRAPOLATION_SECONDS)
              IntOffset(
                (p.left + p.velocityXPx * dtSec).toInt(),
                (p.top + p.velocityYPx * dtSec).toInt(),
              )
            }
            .size(p.widthDp, p.heightDp)
            .graphicsLayer { rotationZ = p.angleDeg }
            .background(Color.Black, RoundedCornerShape(4.dp)),
      )
    }
    for (block in blockGroups) {
      Box(
        modifier =
          Modifier
            .offset {
              val dtSec =
                ((renderTimeNs.longValue - block.baseTimestampNs) / 1e9f)
                  .coerceIn(0f, MAX_EXTRAPOLATION_SECONDS)
              IntOffset(
                (block.left + block.velocityXPx * dtSec).toInt(),
                (block.top + block.velocityYPx * dtSec).toInt(),
              )
            }
            .size(block.widthDp, block.heightDp)
            .background(Color.Black, RoundedCornerShape(4.dp)),
      )
    }
  }
  // Text pass: always on top of every background.
  for (block in blockGroups) {
    Box(
      modifier =
        Modifier
          .offset {
            val dtSec =
              ((renderTimeNs.longValue - block.baseTimestampNs) / 1e9f)
                .coerceIn(0f, MAX_EXTRAPOLATION_SECONDS)
            IntOffset(
              (block.left + block.velocityXPx * dtSec).toInt(),
              (block.top + block.velocityYPx * dtSec).toInt(),
            )
          }
          .size(block.widthDp, block.heightDp)
          .padding(horizontal = 3.dp, vertical = 2.dp),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = block.text,
        color = Color.White,
        style =
          androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(
            fontSize = block.fontSize,
            lineHeight = block.lineHeight,
            textAlign = TextAlign.Center,
          ),
        maxLines = block.maxLines,
        softWrap = true,
        textAlign = TextAlign.Center,
      )
    }
  }
  for (p in perLinePlacements) {
    Box(
      modifier =
        Modifier
          .offset {
            val dtSec =
              ((renderTimeNs.longValue - p.baseTimestampNs) / 1e9f)
                .coerceIn(0f, MAX_EXTRAPOLATION_SECONDS)
            IntOffset(
              (p.left + p.velocityXPx * dtSec).toInt(),
              (p.top + p.velocityYPx * dtSec).toInt(),
            )
          }
          .size(p.widthDp, p.heightDp)
          .graphicsLayer { rotationZ = p.angleDeg }
          .padding(horizontal = 2.dp),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = fittedByPlacement[p]?.text ?: p.text,
        color = Color.White,
        style =
          androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(
            fontSize = fittedGroups[p.groupId]?.fontSize ?: androidx.compose.material3.MaterialTheme.typography.bodySmall.fontSize,
          ),
        maxLines = 1,
        softWrap = false,
      )
    }
  }
}

/** Cap how far forward we'll extrapolate from the last camera-frame
 *  measurement. If the engine stops publishing (background, paused frames),
 *  keeping the overlay frozen is better than running velocity into the void. */
private const val MAX_EXTRAPOLATION_SECONDS: Float = 0.1f

private data class Placement(
  val groupId: String,
  val groupText: String,
  val text: String,
  val left: Int,
  val top: Int,
  val widthPx: Float,
  val heightPx: Float,
  val widthDp: androidx.compose.ui.unit.Dp,
  val heightDp: androidx.compose.ui.unit.Dp,
  val angleDeg: Float,
  /** Per-second preview-space velocity at [baseTimestampNs], so the render-
   *  thread offset lambda can extrapolate this overlay forward at display
   *  refresh rate (60-120Hz) between camera-frame updates (~30Hz). */
  val velocityXPx: Float,
  val velocityYPx: Float,
  val baseTimestampNs: Long,
)

private data class FittedOverlayGroup(
  val fontSize: TextUnit,
  val items: List<FittedPlacement>,
  val block: FittedBlock?,
  val stable: StableLayout,
)

private data class FittedPlacement(
  val placement: Placement,
  val text: String,
)

private data class FittedBlock(
  val text: String,
  val left: Int,
  val top: Int,
  val widthDp: androidx.compose.ui.unit.Dp,
  val heightDp: androidx.compose.ui.unit.Dp,
  val fontSize: TextUnit,
  val lineHeight: TextUnit,
  val maxLines: Int,
  val velocityXPx: Float,
  val velocityYPx: Float,
  val baseTimestampNs: Long,
)

private enum class OverlayLayoutMode {
  PerLine,
  Block,
}

private data class StableLayout(
  val mode: OverlayLayoutMode,
  val fontSizePx: Float,
  val lineCount: Int,
  val geometry: GroupGeometry,
  val slices: List<String> = emptyList(),
)

private data class GroupGeometry(
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
  val linearWidth: Float,
) {
  val width: Float get() = (right - left).coerceAtLeast(1f)
  val height: Float get() = (bottom - top).coerceAtLeast(1f)
}

private fun fitLiveOverlayGroup(
  placements: List<Placement>,
  textMeasurer: androidx.compose.ui.text.TextMeasurer,
  density: androidx.compose.ui.unit.Density,
  baseStyle: TextStyle,
  previous: StableLayout?,
): FittedOverlayGroup {
  val ordered = placements.sortedWith(compareBy<Placement> { it.top }.thenBy { it.left })
  val text = ordered.firstOrNull()?.groupText.orEmpty()
  val geometry = groupGeometry(ordered, with(density) { 4.dp.toPx() })
  if (ordered.isEmpty() || text.isBlank()) {
    return FittedOverlayGroup(
      fontSize = baseStyle.fontSize,
      items = emptyList(),
      block = null,
      stable = StableLayout(OverlayLayoutMode.PerLine, textUnitToPx(baseStyle.fontSize, density), 0, geometry),
    )
  }

  val horizontalPaddingPx = with(density) { 4.dp.toPx() }
  val availableLinearPx =
    ordered.sumOf { max(1.0, (it.widthPx - horizontalPaddingPx).toDouble()) }.toFloat()
  val heights = ordered.map { it.heightPx }.sorted()
  val medianHeightPx = heights[heights.size / 2].coerceAtLeast(1f)
  val maxFontPx = (medianHeightPx * 0.62f).coerceIn(with(density) { 10.sp.toPx() }, with(density) { 30.sp.toPx() })
  val minFontPx = min(maxFontPx, with(density) { 8.sp.toPx() })
  val fittedFontPx =
    shrinkFontToLinearFit(
      text = text,
      availablePx = availableLinearPx,
      startPx = maxFontPx,
      minPx = minFontPx,
      textMeasurer = textMeasurer,
      density = density,
      baseStyle = baseStyle,
    )
  val previousPerLine =
    previous?.takeIf {
      it.mode == OverlayLayoutMode.PerLine &&
        geometry.isCloseTo(it.geometry) &&
        previousPerLineStillFits(it.slices, ordered, horizontalPaddingPx, it.fontSizePx, textMeasurer, density, baseStyle)
    }
  val blockCandidate = shouldUseBlockMode(ordered, text, fittedFontPx, minFontPx, textMeasurer, density, baseStyle, availableLinearPx)
  val previousBlock =
    previous?.takeIf {
      it.mode == OverlayLayoutMode.Block &&
        geometry.isCloseTo(it.geometry)
    }
  val useBlock =
    if (previousPerLine != null) {
      false
    } else if (previousBlock != null) {
      blockCandidate || !oneLineFitsWithSpare(text, availableLinearPx, minFontPx, textMeasurer, density, baseStyle)
    } else {
      blockCandidate
    }
  if (useBlock) {
    return fitLiveOverlayBlock(ordered, text, textMeasurer, density, baseStyle, minFontPx, previous, geometry)
  }
  val fontPx = previousPerLine?.fontSizePx ?: fittedFontPx
  val fontSize = with(density) { fontPx.toSp() }
  val style = baseStyle.copy(fontSize = fontSize)
  val capacities = ordered.map { (it.widthPx - horizontalPaddingPx).coerceAtLeast(1f) }
  val slices = previousPerLine?.slices ?: splitTextAcrossCapacities(text, capacities, textMeasurer, style)
  val lineCount = slices.count { it.isNotBlank() }.coerceAtLeast(1)
  return FittedOverlayGroup(
    fontSize = fontSize,
    items = ordered.mapIndexed { index, placement -> FittedPlacement(placement, slices.getOrElse(index) { "" }) },
    block = null,
    stable = StableLayout(OverlayLayoutMode.PerLine, fontPx, lineCount, geometry, slices),
  )
}

private fun shouldUseBlockMode(
  placements: List<Placement>,
  text: String,
  fittedFontPx: Float,
  minFontPx: Float,
  textMeasurer: androidx.compose.ui.text.TextMeasurer,
  density: androidx.compose.ui.unit.Density,
  baseStyle: TextStyle,
  availableLinearPx: Float,
): Boolean {
  if (placements.size < 2) return false
  if (placementsHaveInflatedOverlap(placements, inflatePx = with(density) { 2.dp.toPx() })) return true
  if (perLineTextWouldOverlap(placements, fittedFontPx)) return true
  val minStyle = baseStyle.copy(fontSize = with(density) { minFontPx.toSp() })
  val fitsAtMinimum = measureOneLineWidth(text, textMeasurer, minStyle) <= availableLinearPx
  if (!fitsAtMinimum || fittedFontPx <= minFontPx + 0.25f) return true
  return false
}

private fun perLineTextWouldOverlap(
  placements: List<Placement>,
  fontSizePx: Float,
): Boolean {
  val ordered = placements.sortedBy { it.top + it.heightPx * 0.5f }
  val minCenterGap = fontSizePx * 1.18f
  for (i in 0 until ordered.lastIndex) {
    val a = ordered[i]
    val b = ordered[i + 1]
    val acy = a.top + a.heightPx * 0.5f
    val bcy = b.top + b.heightPx * 0.5f
    val horizontalOverlap = min(a.left + a.widthPx, b.left + b.widthPx) > max(a.left.toFloat(), b.left.toFloat())
    if (horizontalOverlap && abs(bcy - acy) < minCenterGap) return true
  }
  return false
}

private fun groupedPlacementsStableKeys(grouped: Map<String, List<Placement>>): Set<String> =
  grouped.map { (groupId, placements) ->
    stableLayoutKey(groupId, placements.firstOrNull()?.groupText.orEmpty())
  }.toSet()

private fun stableLayoutKey(
  groupId: String,
  text: String,
): String = "$groupId\u0000$text"

private fun groupGeometry(
  placements: List<Placement>,
  horizontalPaddingPx: Float,
): GroupGeometry {
  if (placements.isEmpty()) {
    return GroupGeometry(0f, 0f, 1f, 1f, 1f)
  }
  return GroupGeometry(
    left = placements.minOf { it.left }.toFloat(),
    top = placements.minOf { it.top }.toFloat(),
    right = placements.maxOf { it.left + it.widthPx },
    bottom = placements.maxOf { it.top + it.heightPx },
    linearWidth = placements.sumOf { max(1.0, (it.widthPx - horizontalPaddingPx).toDouble()) }.toFloat(),
  )
}

private fun GroupGeometry.isCloseTo(previous: GroupGeometry): Boolean {
  fun ratioDelta(
    a: Float,
    b: Float,
  ): Float = abs(a - b) / max(max(a, b), 1f)
  val centerX = (left + right) * 0.5f
  val centerY = (top + bottom) * 0.5f
  val previousCenterX = (previous.left + previous.right) * 0.5f
  val previousCenterY = (previous.top + previous.bottom) * 0.5f
  val centerMove =
    max(abs(centerX - previousCenterX) / max(width, previous.width), abs(centerY - previousCenterY) / max(height, previous.height))
  return centerMove <= 0.15f &&
    ratioDelta(width, previous.width) <= 0.18f &&
    ratioDelta(height, previous.height) <= 0.18f &&
    ratioDelta(linearWidth, previous.linearWidth) <= 0.18f
}

private fun previousPerLineStillFits(
  slices: List<String>,
  placements: List<Placement>,
  horizontalPaddingPx: Float,
  fontSizePx: Float,
  textMeasurer: androidx.compose.ui.text.TextMeasurer,
  density: androidx.compose.ui.unit.Density,
  baseStyle: TextStyle,
): Boolean {
  if (slices.size != placements.size || slices.isEmpty()) return false
  val style = baseStyle.copy(fontSize = with(density) { fontSizePx.toSp() })
  return slices.zip(placements).all { (slice, placement) ->
    if (slice.isBlank()) {
      true
    } else {
      val capacity = (placement.widthPx - horizontalPaddingPx).coerceAtLeast(1f)
      measureOneLineWidth(slice, textMeasurer, style) <= capacity * 1.08f
    }
  }
}

private fun previousBlockStillFits(
  text: String,
  widthPx: Float,
  heightPx: Float,
  fontSizePx: Float,
  textMeasurer: androidx.compose.ui.text.TextMeasurer,
  density: androidx.compose.ui.unit.Density,
  baseStyle: TextStyle,
): Boolean {
  val layout = measureWrappedText(text, textMeasurer, liveOverlayTextStyle(baseStyle, density, fontSizePx), widthPx)
  return layout.size.height <= heightPx * 1.08f
}

private fun oneLineFitsWithSpare(
  text: String,
  availableLinearPx: Float,
  minFontPx: Float,
  textMeasurer: androidx.compose.ui.text.TextMeasurer,
  density: androidx.compose.ui.unit.Density,
  baseStyle: TextStyle,
): Boolean {
  val width = measureOneLineWidth(text, textMeasurer, baseStyle.copy(fontSize = with(density) { minFontPx.toSp() }))
  return width <= availableLinearPx * 0.85f
}

private fun liveOverlayTextStyle(
  baseStyle: TextStyle,
  density: androidx.compose.ui.unit.Density,
  fontPx: Float,
  textAlign: TextAlign = TextAlign.Unspecified,
): TextStyle {
  return baseStyle.copy(
    fontSize = with(density) { fontPx.toSp() },
    lineHeight = with(density) { (fontPx * 1.12f).toSp() },
    textAlign = textAlign,
  )
}

private fun textUnitToPx(
  value: TextUnit,
  density: androidx.compose.ui.unit.Density,
): Float =
  if (value.isSp) {
    with(density) { value.toPx() }
  } else {
    with(density) { 14.sp.toPx() }
  }

private fun placementsHaveInflatedOverlap(
  placements: List<Placement>,
  inflatePx: Float,
): Boolean {
  for (i in placements.indices) {
    for (j in i + 1 until placements.size) {
      if (rectsOverlap(placements[i], placements[j], inflatePx)) return true
    }
  }
  return false
}

private fun rectsOverlap(
  a: Placement,
  b: Placement,
  inflatePx: Float,
): Boolean {
  val aLeft = a.left - inflatePx
  val aTop = a.top - inflatePx
  val aRight = a.left + a.widthPx + inflatePx
  val aBottom = a.top + a.heightPx + inflatePx
  val bLeft = b.left - inflatePx
  val bTop = b.top - inflatePx
  val bRight = b.left + b.widthPx + inflatePx
  val bBottom = b.top + b.heightPx + inflatePx
  return aLeft < bRight && aRight > bLeft && aTop < bBottom && aBottom > bTop
}

private fun fitLiveOverlayBlock(
  placements: List<Placement>,
  text: String,
  textMeasurer: androidx.compose.ui.text.TextMeasurer,
  density: androidx.compose.ui.unit.Density,
  baseStyle: TextStyle,
  minFontPx: Float,
  previous: StableLayout?,
  geometry: GroupGeometry,
): FittedOverlayGroup {
  val padPx = with(density) { 3.dp.toPx() }
  val leftPx = placements.minOf { it.left }.toFloat() - padPx
  val topPx = placements.minOf { it.top }.toFloat() - padPx
  val rightPx = placements.maxOf { it.left + it.widthPx } + padPx
  val bottomPx = placements.maxOf { it.top + it.heightPx } + padPx
  val widthPx = (rightPx - leftPx).coerceAtLeast(1f)
  val heightPx = (bottomPx - topPx).coerceAtLeast(1f)
  val contentWidthPx = (widthPx - padPx * 2f).coerceAtLeast(1f)
  val contentHeightPx = (heightPx - padPx * 2f).coerceAtLeast(1f)
  val blockMinFontPx = min(minFontPx, with(density) { 6.sp.toPx() })
  val heights = placements.map { it.heightPx }.sorted()
  val sourceFontCapPx =
    (heights[heights.size / 2].coerceAtLeast(1f) * 0.62f)
      .coerceIn(blockMinFontPx, with(density) { 30.sp.toPx() })
  val startFontPx =
    (contentHeightPx / max(1, estimateLineCount(text, contentWidthPx, textMeasurer, baseStyle, with(density) { minFontPx.toSp() })))
      .coerceIn(blockMinFontPx, sourceFontCapPx)
  val fittedFontPx =
    shrinkFontToBlockFit(
      text = text,
      widthPx = contentWidthPx,
      heightPx = contentHeightPx,
      startPx = startFontPx,
      minPx = blockMinFontPx,
      textMeasurer = textMeasurer,
      density = density,
      baseStyle = baseStyle,
    )
  val previousBlock =
    previous?.takeIf {
      it.mode == OverlayLayoutMode.Block &&
        geometry.isCloseTo(it.geometry) &&
        previousBlockStillFits(text, contentWidthPx, contentHeightPx, it.fontSizePx, textMeasurer, density, baseStyle)
    }
  val fontPx = previousBlock?.fontSizePx ?: fittedFontPx
  val fontSize = with(density) { fontPx.toSp() }
  val lineHeight = with(density) { (fontPx * 1.12f).toSp() }
  val layout = measureWrappedText(text, textMeasurer, liveOverlayTextStyle(baseStyle, density, fontPx, TextAlign.Center), contentWidthPx)
  val fittedHeightPx = max(heightPx, layout.size.height + padPx * 2f)
  val fittedTopPx = (((topPx + bottomPx) * 0.5f) - fittedHeightPx * 0.5f).coerceAtLeast(0f)
  // Block velocity = mean of constituents. Good enough for short extrapolation
  // windows; the block snaps to the next correct position on the next camera
  // frame anyway.
  val avgVx = if (placements.isEmpty()) 0f else placements.map { it.velocityXPx }.average().toFloat()
  val avgVy = if (placements.isEmpty()) 0f else placements.map { it.velocityYPx }.average().toFloat()
  val blockBaseTsNs = placements.firstOrNull()?.baseTimestampNs ?: 0L
  return FittedOverlayGroup(
    fontSize = fontSize,
    items = emptyList(),
    block =
      FittedBlock(
        text = text,
        left = leftPx.toInt(),
        top = fittedTopPx.toInt(),
        widthDp = with(density) { widthPx.toDp() },
        heightDp = with(density) { fittedHeightPx.toDp() },
        fontSize = fontSize,
        lineHeight = lineHeight,
        maxLines = max(1, layout.lineCount),
        velocityXPx = avgVx,
        velocityYPx = avgVy,
        baseTimestampNs = blockBaseTsNs,
      ),
    stable = StableLayout(OverlayLayoutMode.Block, fontPx, max(1, layout.lineCount), geometry),
  )
}

private fun shrinkFontToLinearFit(
  text: String,
  availablePx: Float,
  startPx: Float,
  minPx: Float,
  textMeasurer: androidx.compose.ui.text.TextMeasurer,
  density: androidx.compose.ui.unit.Density,
  baseStyle: TextStyle,
): Float {
  if (text.isBlank()) return startPx
  var lo = minPx
  var hi = startPx
  repeat(8) {
    val mid = (lo + hi) * 0.5f
    val width = measureOneLineWidth(text, textMeasurer, baseStyle.copy(fontSize = with(density) { mid.toSp() }))
    if (width <= availablePx) {
      lo = mid
    } else {
      hi = mid
    }
  }
  return lo
}

private fun shrinkFontToBlockFit(
  text: String,
  widthPx: Float,
  heightPx: Float,
  startPx: Float,
  minPx: Float,
  textMeasurer: androidx.compose.ui.text.TextMeasurer,
  density: androidx.compose.ui.unit.Density,
  baseStyle: TextStyle,
): Float {
  if (text.isBlank()) return startPx
  var lo = minPx
  var hi = startPx.coerceAtLeast(minPx)
  repeat(8) {
    val mid = (lo + hi) * 0.5f
    val layout = measureWrappedText(text, textMeasurer, liveOverlayTextStyle(baseStyle, density, mid), widthPx)
    if (layout.size.height <= heightPx) {
      lo = mid
    } else {
      hi = mid
    }
  }
  return lo
}

private fun estimateLineCount(
  text: String,
  widthPx: Float,
  textMeasurer: androidx.compose.ui.text.TextMeasurer,
  baseStyle: TextStyle,
  fontSize: TextUnit,
): Int = measureWrappedText(text, textMeasurer, baseStyle.copy(fontSize = fontSize), widthPx).lineCount

private fun splitTextAcrossCapacities(
  text: String,
  capacitiesPx: List<Float>,
  textMeasurer: androidx.compose.ui.text.TextMeasurer,
  style: TextStyle,
): List<String> {
  if (capacitiesPx.isEmpty()) return emptyList()
  val output = MutableList(capacitiesPx.size) { "" }
  val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
  if (tokens.isEmpty()) return output

  var tokenIndex = 0
  for (slot in capacitiesPx.indices) {
    if (tokenIndex >= tokens.size) break
    if (slot == capacitiesPx.lastIndex) {
      output[slot] = tokens.drop(tokenIndex).joinToString(" ")
      break
    }
    var candidate = ""
    while (tokenIndex < tokens.size) {
      val next = if (candidate.isEmpty()) tokens[tokenIndex] else "$candidate ${tokens[tokenIndex]}"
      if (measureOneLineWidth(next, textMeasurer, style) > capacitiesPx[slot] && candidate.isNotEmpty()) break
      candidate = next
      tokenIndex++
      if (measureOneLineWidth(candidate, textMeasurer, style) > capacitiesPx[slot]) break
    }
    output[slot] = candidate
  }
  return output
}

private fun measureWrappedText(
  text: String,
  textMeasurer: androidx.compose.ui.text.TextMeasurer,
  style: TextStyle,
  widthPx: Float,
): androidx.compose.ui.text.TextLayoutResult =
  textMeasurer.measure(
    text = AnnotatedString(text),
    style = style,
    softWrap = true,
    constraints = Constraints(maxWidth = widthPx.toInt().coerceAtLeast(1)),
  )

private fun measureOneLineWidth(
  text: String,
  textMeasurer: androidx.compose.ui.text.TextMeasurer,
  style: TextStyle,
): Int =
  textMeasurer.measure(
    text = AnnotatedString(text),
    style = style,
    maxLines = 1,
    softWrap = false,
  ).size.width
