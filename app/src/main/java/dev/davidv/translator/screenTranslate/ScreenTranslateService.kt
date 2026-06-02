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

package dev.davidv.translator.screenTranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import dev.davidv.translator.R
import dev.davidv.translator.TranslatorApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 *  Foreground service for "translate screen": holds the [MediaProjection],
 *  mirrors the display into a [ScreenCaptureGlWorker]'s capture surface, and
 *  floats a translucent overlay window. The overlay is a hardware Canvas
 *  [ScreenOverlayView] (not a SurfaceView) at α≈0.79 so it composites through
 *  the window — which both dims it under the untrusted-touch cap (taps fall
 *  through to the app underneath) and is non-secure (so the capture isn't
 *  blacked out). Stop is a notification action. Feedback (the overlay landing
 *  in the capture) is accepted for this iteration.
 */
class ScreenTranslateService : Service() {
  private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

  private var projection: MediaProjection? = null
  private var virtualDisplay: VirtualDisplay? = null
  private var screenTracker: uniffi.bindings.LiveScreenTracker? = null
  private var overlayView: GpuOverlayTextureView? = null
  private var worker: ScreenCaptureGlWorker? = null
  private var windowManager: WindowManager? = null
  private var stopped = false

  private var displayManager: DisplayManager? = null

  // Display size the current capture was built for; a rotation changes these and
  // triggers an in-place resize (the VirtualDisplay is fixed to its creation
  // dimensions, so a stale portrait capture letterboxes a landscape screen to an
  // unreadable strip — but on Android 14+ it can't be recreated, only resized).
  private var lastW = 0
  private var lastH = 0

  private val displayListener =
    object : DisplayManager.DisplayListener {
      override fun onDisplayAdded(displayId: Int) {}

      override fun onDisplayRemoved(displayId: Int) {}

      override fun onDisplayChanged(displayId: Int) {
        if (stopped || displayId != Display.DEFAULT_DISPLAY) return
        val wm = windowManager ?: return
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        if (w == lastW && h == lastH) return
        Log.i(TAG, "display changed (${lastW}x$lastH → ${w}x$h) → resize capture")
        resizeCapture(w, h, metrics.densityDpi)
      }
    }

  private val projectionCallback =
    object : MediaProjection.Callback() {
      override fun onStop() {
        Log.i(TAG, "MediaProjection stopped")
        stopEverything()
      }
    }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    if (intent == null || intent.action == ACTION_STOP) {
      stopEverything()
      return START_NOT_STICKY
    }

    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
    val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
    if (resultCode == 0 || data == null) {
      Log.e(TAG, "Missing projection token; stopping")
      stopEverything()
      return START_NOT_STICKY
    }

    startForegroundNotification()

    val app = applicationContext as TranslatorApplication
    val catalog = app.languageCatalog
    if (catalog == null) {
      Log.e(TAG, "No language catalog; stopping")
      stopEverything()
      return START_NOT_STICKY
    }

    val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    val proj = pm.getMediaProjection(resultCode, data)
    if (proj == null) {
      Log.e(TAG, "getMediaProjection returned null; stopping")
      stopEverything()
      return START_NOT_STICKY
    }
    proj.registerCallback(projectionCallback, null)
    projection = proj

    val tracker = uniffi.bindings.LiveScreenTracker(catalog.planarHandle())
    screenTracker = tracker
    configureLanguages(
      tracker,
      app,
      sourceCode = intent.getStringExtra(EXTRA_SOURCE_LANG),
      targetCode = intent.getStringExtra(EXTRA_TARGET_LANG),
      isAutoSource = intent.getBooleanExtra(EXTRA_AUTO_SOURCE, true),
    )

    setupOverlayAndCapture(tracker, proj)

    if (displayManager == null) {
      val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
      displayManager = dm
      dm.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    return START_NOT_STICKY
  }

  private fun configureLanguages(
    tracker: uniffi.bindings.LiveScreenTracker,
    app: TranslatorApplication,
    sourceCode: String?,
    targetCode: String?,
    isAutoSource: Boolean,
  ) {
    val catalog = app.languageCatalog ?: return
    val settings = app.settingsManager.settings.value
    // Target: the caller's choice (assistant selection) → app default → en.
    val to =
      catalog.languageByCode(targetCode ?: settings.defaultTargetLanguageCode)
        ?: catalog.languageByCode("en")
        ?: return
    // Source: forced code skips the script classifier; auto leaves it on (the
    // `from` value is then just a hint).
    val from = if (isAutoSource) to else (sourceCode?.let { catalog.languageByCode(it) } ?: to)
    tracker.setLanguages(from.code, to.code, isAutoSource)
    Log.i(TAG, "languages: from=${from.code} to=${to.code} auto=$isAutoSource")
  }

  private fun setupOverlayAndCapture(
    tracker: uniffi.bindings.LiveScreenTracker,
    proj: MediaProjection,
  ) {
    val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    windowManager = wm

    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    wm.defaultDisplay.getRealMetrics(metrics)
    val w = metrics.widthPixels
    val h = metrics.heightPixels
    val dpi = metrics.densityDpi
    lastW = w
    lastH = h

    val view = GpuOverlayTextureView(this)
    overlayView = view

    val overlayType =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
      }

    val params =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        overlayType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
          WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
      )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      params.layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }
    // Android 12+ untrusted-touch rule: a tap passing through an overlay owned
    // by another app is discarded if the overlay's opacity exceeds ~0.8
    // (maximum_obscuring_opacity_for_touch). Sit just under the cap — and
    // because the TextureView composites through the window (not a SurfaceView,
    // a separate full-opacity layer), the window alpha governs it, so taps fall
    // through. With an accessibility service the app is trusted and the cap
    // lifts; the only change is raising WINDOW_ALPHA — the GPU render path is
    // identical, so there is no second code path for that state.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      params.alpha = WINDOW_ALPHA
    }
    wm.addView(view, params)

    val pipelinePtr = tracker.rawAddressForJni().toLong()
    val captureWorker = ScreenCaptureGlWorker(w, h)
    captureWorker.pipelinePtr = pipelinePtr
    worker = captureWorker
    // The worker turns the TextureView's SurfaceTexture into the EGL window
    // surface it presents into; hand each lifecycle edge straight to it.
    view.onSurfaceTexture = { st -> captureWorker.setOutputSurfaceTexture(st) }
    captureWorker.start()
    Log.i(TAG, "setup: ${w}x$h @${dpi}dpi, pipelinePtr=$pipelinePtr")

    scope.launch {
      val surface = withContext(Dispatchers.IO) { captureWorker.awaitSourceSurface() }
      if (stopped) return@launch
      if (surface == null) {
        Log.e(TAG, "GL capture surface never became ready; stopping")
        stopEverything()
        return@launch
      }
      // Size the SurfaceTexture now that it exists; a VirtualDisplay won't
      // produce frames into a 0×0 buffer.
      captureWorker.setSourceBufferSize(w, h)
      virtualDisplay =
        proj.createVirtualDisplay(
          "translator-screen",
          w,
          h,
          dpi,
          DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
          surface,
          null,
          null,
        )
      Log.i(TAG, "VirtualDisplay created ${w}x$h, surface=$surface")
    }
  }

  private fun startForegroundNotification() {
    val channelId = "screen_translate"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val nm = getSystemService(NotificationManager::class.java)
      if (nm.getNotificationChannel(channelId) == null) {
        nm.createNotificationChannel(
          NotificationChannel(
            channelId,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW,
          ),
        )
      }
    }
    val stopIntent =
      Intent(this, ScreenTranslateService::class.java).apply { action = ACTION_STOP }
    val stopPending =
      android.app.PendingIntent.getService(
        this,
        0,
        stopIntent,
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val notification: Notification =
      androidx.core.app.NotificationCompat.Builder(this, channelId)
        .setContentTitle(getString(R.string.app_name))
        .setContentText("Translating screen")
        .setSmallIcon(R.drawable.ic_translate_button)
        .setOngoing(true)
        .addAction(R.drawable.cancel, "Stop", stopPending)
        .build()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  /** Rotation / display-geometry change: resize the capture **in place**. On
   *  Android 14+ a [MediaProjection] permits only one `createVirtualDisplay`, so
   *  the VirtualDisplay must never be recreated — `resize` it and the worker
   *  instead. The overlay window is MATCH_PARENT, so it follows the rotation and
   *  re-hands its SurfaceTexture (→ the worker recreates its present surface).
   *  Old portrait overlays are dropped; the native monitor rebuilds its lattice
   *  when the canonical dimensions change. */
  private fun resizeCapture(
    w: Int,
    h: Int,
    dpi: Int,
  ) {
    worker?.resize(w, h)
    runCatching { virtualDisplay?.resize(w, h, dpi) }
    runCatching { screenTracker?.clearOverlay() }
    lastW = w
    lastH = h
    Log.i(TAG, "capture resized to ${w}x$h @${dpi}dpi")
  }

  private fun stopEverything() {
    if (stopped) return
    stopped = true
    runCatching { displayManager?.unregisterDisplayListener(displayListener) }
    displayManager = null
    runCatching { virtualDisplay?.release() }
    virtualDisplay = null
    runCatching {
      projection?.unregisterCallback(projectionCallback)
      projection?.stop()
    }
    projection = null
    runCatching { worker?.stop() }
    worker = null
    val wm = windowManager
    overlayView?.let { runCatching { wm?.removeView(it) } }
    overlayView = null
    runCatching { screenTracker?.clearOverlay() }
    runCatching { screenTracker?.destroy() }
    screenTracker = null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
    stopSelf()
  }

  override fun onDestroy() {
    stopEverything()
    scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    super.onDestroy()
  }

  companion object {
    private const val TAG = "ScreenTranslateSvc"
    private const val NOTIFICATION_ID = 0x5C12

    /** Overlay window opacity. Sits just under the Android 12+ untrusted-touch
     *  cap (~0.8) so taps fall through to the app below; the GPU overlay is
     *  dimmed by this. With an accessibility service the cap lifts and this can
     *  go to 1.0 — the only change for that state, no separate render path. */
    private const val WINDOW_ALPHA = 0.79f
    const val EXTRA_RESULT_CODE = "result_code"
    const val EXTRA_DATA = "data"
    const val EXTRA_SOURCE_LANG = "source_lang"
    const val EXTRA_TARGET_LANG = "target_lang"
    const val EXTRA_AUTO_SOURCE = "auto_source"
    const val ACTION_STOP = "dev.davidv.translator.STOP_SCREEN_TRANSLATE"

    fun startIntent(
      context: Context,
      resultCode: Int,
      data: Intent,
      sourceCode: String?,
      targetCode: String?,
      isAutoSource: Boolean,
    ): Intent =
      Intent(context, ScreenTranslateService::class.java).apply {
        putExtra(EXTRA_RESULT_CODE, resultCode)
        putExtra(EXTRA_DATA, data)
        sourceCode?.let { putExtra(EXTRA_SOURCE_LANG, it) }
        targetCode?.let { putExtra(EXTRA_TARGET_LANG, it) }
        putExtra(EXTRA_AUTO_SOURCE, isAutoSource)
      }
  }
}
