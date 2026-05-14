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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
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
import dev.davidv.translator.R
import dev.davidv.translator.TranslatorMessage
import dev.davidv.translator.ui.components.LanguageSelector
import java.io.File
import java.util.concurrent.Executor
import android.util.Size as AndroidSize

private const val TAG = "LiveCameraScreen"
private val TARGET_RESOLUTION = AndroidSize(1080, 1920)

@Composable
fun LiveCameraScreen(
  from: Language,
  to: Language,
  canSwap: Boolean,
  languageState: LanguageAvailabilityState,
  languageMetadata: Map<Language, LanguageMetadata>,
  onMessage: (TranslatorMessage) -> Unit,
  liveOverlayDefaultEnabled: Boolean,
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

  Box(modifier = Modifier.fillMaxSize()) {
    AndroidView(
      factory = { previewView },
      modifier = Modifier.fillMaxSize(),
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
