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
import android.graphics.Bitmap
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageAvailabilityState
import dev.davidv.translator.LanguageMetadata
import dev.davidv.translator.LiveOcrEngine
import dev.davidv.translator.LiveOverlayItem
import dev.davidv.translator.R
import dev.davidv.translator.TranslatorMessage
import dev.davidv.translator.ui.components.LanguageSelector
import java.io.File
import java.util.concurrent.Executor
import android.util.Size as AndroidSize

private const val TAG = "LiveCameraScreen"
private val TARGET_RESOLUTION = AndroidSize(1080, 1920)
private val ANALYZER_RESOLUTION = AndroidSize(1080, 1920)

@Composable
fun LiveCameraScreen(
  from: Language,
  to: Language,
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
  var liveOverlayOn by remember { mutableStateOf(liveOverlayDefaultEnabled) }
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
  DisposableEffect(liveOverlayOn, liveOcrEngine, from.code, to.code) {
    val engine = liveOcrEngine
    val mySession = analyzerSession.incrementAndGet()
    if (liveOverlayOn && engine != null) {
      imageAnalysis.setAnalyzer(analyzerExecutor) { proxy ->
        val tConvert = System.nanoTime()
        val bitmap = proxyToBitmap(proxy)
        proxy.close()
        val convertMs = (System.nanoTime() - tConvert) / 1_000_000.0
        if (bitmap == null) return@setAnalyzer
        if (analyzerSession.get() != mySession) return@setAnalyzer
        val fx = cropFocusNormalized.x
        val fy = cropFocusNormalized.y
        kotlinx.coroutines.runBlocking { engine.submitFrame(bitmap, fx, fy, from, to, convertMs) }
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
      overlays = liveOverlays,
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
      onLiveOverlayToggle = { liveOverlayOn = !liveOverlayOn },
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
  canSwap: Boolean,
  languageState: LanguageAvailabilityState,
  languageMetadata: Map<Language, LanguageMetadata>,
  onMessage: (TranslatorMessage) -> Unit,
  modifier: Modifier = Modifier,
) {
  val fromLanguages =
    languageState.allLanguages().filter { x ->
      x != from && (languageState.availabilityFor(x)?.hasToEnglish == true || x.isEnglish)
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
        tint = if (liveOverlayOn) Color.White else Color.White.copy(alpha = 0.4f),
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
  val density = androidx.compose.ui.platform.LocalDensity.current
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
      Placement(item.translatedText, leftPx, topPx, widthDp, heightDp, angleDeg)
    }

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
    for (p in placements) {
      Box(
        modifier =
          Modifier
            .offset { IntOffset(p.left, p.top) }
            .size(p.widthDp, p.heightDp)
            .graphicsLayer { rotationZ = p.angleDeg }
            .background(Color.Black, RoundedCornerShape(4.dp)),
      )
    }
  }
  // Text pass: always on top of every background.
  for (p in placements) {
    Box(
      modifier =
        Modifier
          .offset { IntOffset(p.left, p.top) }
          .size(p.widthDp, p.heightDp)
          .graphicsLayer { rotationZ = p.angleDeg }
          .padding(horizontal = 2.dp),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = p.text,
        color = Color.White,
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
      )
    }
  }
}

private data class Placement(
  val text: String,
  val left: Int,
  val top: Int,
  val widthDp: androidx.compose.ui.unit.Dp,
  val heightDp: androidx.compose.ui.unit.Dp,
  val angleDeg: Float,
)

private fun proxyToBitmap(proxy: ImageProxy): Bitmap? {
  return try {
    val plane = proxy.planes[0]
    val buffer = plane.buffer
    buffer.rewind()
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val width = proxy.width
    val height = proxy.height

    val srcBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    if (rowStride == width * pixelStride) {
      srcBitmap.copyPixelsFromBuffer(buffer)
    } else {
      val bytes = ByteArray(buffer.remaining())
      buffer.get(bytes)
      val packed = ByteArray(width * pixelStride * height)
      for (row in 0 until height) {
        System.arraycopy(bytes, row * rowStride, packed, row * width * pixelStride, width * pixelStride)
      }
      srcBitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(packed))
    }

    // Rotate to display orientation. The detector's `estimate_horizontal_tilt`
    // assumes mostly-horizontal text in its input, so we have to align the bitmap to
    // the display before detection (otherwise vertical-in-sensor text gets treated
    // as axis-aligned and the overlay angles come out wrong). 90° is a pure
    // permutation — filter=false skips unneeded interpolation.
    val rotation = proxy.imageInfo.rotationDegrees
    if (rotation == 0) {
      srcBitmap
    } else {
      val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
      val rotated = Bitmap.createBitmap(srcBitmap, 0, 0, width, height, matrix, false)
      if (rotated !== srcBitmap) srcBitmap.recycle()
      rotated
    }
  } catch (e: Exception) {
    Log.w(TAG, "frame conversion failed", e)
    null
  }
}
