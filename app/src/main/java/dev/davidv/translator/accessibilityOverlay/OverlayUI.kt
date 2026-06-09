package dev.davidv.translator.accessibilityOverlay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import dev.davidv.translator.Language
import dev.davidv.translator.MainActivity
import dev.davidv.translator.ReadingOrder
import dev.davidv.translator.SettingsManager
import dev.davidv.translator.assistantOverlay.BorderWaveView
import dev.davidv.translator.overlayChrome.FloatingBubble
import dev.davidv.translator.overlayChrome.OverlayChromeFactory
import dev.davidv.translator.overlayChrome.OverlayMenuHost
import dev.davidv.translator.overlayChrome.OverlayMenuManager

class OverlayUI(
  private val service: TranslatorAccessibilityService,
  private val windowManager: WindowManager,
  private val settingsManager: SettingsManager,
) {
  private val handler = Handler(Looper.getMainLooper())
  private var launcherBubble: FloatingBubble? = null
  private var toolbarView: View? = null
  private var sourceLabelView: TextView? = null
  private var targetLabelView: TextView? = null
  private var readingOrderButtonView: View? = null
  private var readingOrderIconView: ImageView? = null
  private val translationOverlays = mutableListOf<View>()
  private var touchWatcher: View? = null
  private var borderView: BorderWaveView? = null

  private val menuManager =
    OverlayMenuManager(
      service,
      ::dpToPx,
      object : OverlayMenuHost {
        override fun addDismissLayer(view: View) {
          val params =
            WindowManager.LayoutParams(
              WindowManager.LayoutParams.MATCH_PARENT,
              WindowManager.LayoutParams.MATCH_PARENT,
              WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
              WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
              PixelFormat.TRANSLUCENT,
            )
          params.windowAnimations = 0
          windowManager.addView(view, params)
        }

        override fun addMenuView(view: View) {
          val params =
            WindowManager.LayoutParams(
              dpToPx(180),
              WindowManager.LayoutParams.WRAP_CONTENT,
              WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
              WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
              PixelFormat.TRANSLUCENT,
            )
          params.gravity = Gravity.TOP or Gravity.END
          params.x = dpToPx(8)
          params.y = dpToPx(48)
          params.windowAnimations = 0
          windowManager.addView(view, params)
        }

        override fun addPickerView(view: View) {
          val params =
            WindowManager.LayoutParams(
              dpToPx(250),
              dpToPx(400),
              WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
              WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
              PixelFormat.TRANSLUCENT,
            )
          params.gravity = Gravity.CENTER
          params.windowAnimations = 0
          windowManager.addView(view, params)
        }

        override fun removeMenuChild(view: View) {
          windowManager.removeView(view)
        }
      },
    )

  fun showFloatingButton() {
    val bubble = launcherBubble
    if (bubble == null) {
      launcherBubble =
        FloatingBubble(service, windowManager, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, ::dpToPx) {
          service.activate()
        }.also { it.show() }
    } else {
      bubble.setShown(true)
    }
  }

  fun removeFloatingButton() {
    launcherBubble?.setShown(false)
  }

  fun restoreFloatingButton() {
    showFloatingButton()
  }

  fun showToolbar(
    forcedSourceLanguage: Language?,
    forcedTargetLanguage: Language?,
    readingOrder: ReadingOrder?,
    isAutoSource: Boolean,
  ) {
    if (toolbarView != null) return

    val toolbarViews =
      OverlayChromeFactory.createLanguageToolbar(
        context = service,
        dpToPx = ::dpToPx,
        forcedSourceLanguage = forcedSourceLanguage,
        forcedTargetLanguage = forcedTargetLanguage,
        defaultTargetLanguage = service.langStateManager.languageByCode(settingsManager.settings.value.defaultTargetLanguageCode) ?: return,
        onClose = { service.deactivate() },
        onSourceClick = { service.showLanguagePicker(true) },
        onSwap = { service.swapLanguages() },
        onTargetClick = { service.showLanguagePicker(false) },
        showReadingOrderButton = forcedSourceLanguage?.code == "ja",
        readingOrder = readingOrder,
        onReadingOrderClick = { service.toggleJapaneseOcrMode() },
        onRefreshClick = { service.handleFullScreenOcr() },
        onTranslateScreenClick = { service.startScreenTranslate() },
        onMenuClick = { service.showDotsMenu() },
        isAutoSource = isAutoSource,
      )
    val toolbar = toolbarViews.root
    sourceLabelView = toolbarViews.sourceLabel
    targetLabelView = toolbarViews.targetLabel
    readingOrderButtonView = toolbarViews.readingOrderButton
    readingOrderIconView = toolbarViews.readingOrderIcon

    val params =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
      )
    params.gravity = Gravity.TOP or Gravity.START
    params.x = 0
    params.y = getStatusBarHeight()
    params.windowAnimations = 0

    windowManager.addView(toolbar, params)
    toolbarView = toolbar
  }

  fun removeToolbar() {
    toolbarView?.let {
      windowManager.removeView(it)
      toolbarView = null
      sourceLabelView = null
      targetLabelView = null
      readingOrderButtonView = null
      readingOrderIconView = null
    }
  }

  fun updateToolbarState(
    forcedSourceLanguage: Language?,
    forcedTargetLanguage: Language?,
    readingOrder: ReadingOrder?,
    isAutoSource: Boolean,
  ) {
    sourceLabelView?.text = OverlayChromeFactory.formatSourceLabel(forcedSourceLanguage, isAutoSource)
    val currentTarget = forcedTargetLanguage ?: service.langStateManager.languageByCode(settingsManager.settings.value.defaultTargetLanguageCode)
    targetLabelView?.text = currentTarget?.shortDisplayName ?: "?"
    OverlayChromeFactory.updateReadingOrderButtonState(
      readingButton = readingOrderButtonView,
      readingIcon = readingOrderIconView,
      visible = forcedSourceLanguage?.code == "ja",
      readingOrder = readingOrder,
    )
  }

  fun showDotsMenu() {
    menuManager.showDotsMenu(
      listOf(
        "Copy original text" to {
          copyToClipboard("Original text", service.lastOriginalText)
        },
        "Copy translated text" to {
          copyToClipboard("Translated text", service.lastTranslatedText)
        },
        "Open App" to {
          service.deactivate()
          val intent = Intent(service, MainActivity::class.java)
          intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          service.startActivity(intent)
        },
        "Disable Service" to {
          service.deactivate()
          service.disableSelf()
        },
      ),
    )
  }

  private fun copyToClipboard(
    label: String,
    text: String,
  ) {
    if (text.isBlank()) {
      showOverlayMessage("Nothing to copy yet")
      return
    }
    val clipboard = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    showOverlayMessage("Copied $label")
  }

  fun showLanguagePicker(
    isSource: Boolean,
    availableLangs: List<Language>,
    onPick: (Language?) -> Unit,
  ) {
    menuManager.showLanguagePicker(isSource, availableLangs) { lang ->
      onPick(lang)
    }
  }

  fun dismissMenu() {
    menuManager.dismiss()
  }

  fun showBitmapOverlay(
    bitmap: Bitmap,
    bounds: Rect,
  ) {
    val imageView = ImageView(service)
    imageView.setImageBitmap(bitmap)
    imageView.scaleType = ImageView.ScaleType.FIT_XY

    // Non-touchable so taps and swipes pass straight through to the app underneath;
    // the overlay clears itself off the next scroll/click via onAccessibilityEvent.
    val params =
      WindowManager.LayoutParams(
        bounds.width(),
        bounds.height(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
      )
    params.gravity = Gravity.TOP or Gravity.START
    params.x = bounds.left
    params.y = bounds.top

    windowManager.addView(imageView, params)
    translationOverlays.add(imageView)
    ensureTouchWatcher()
  }

  /** A 1×1 watcher window: WATCH_OUTSIDE_TOUCH fires ACTION_OUTSIDE on the *down* of
   *  any touch anywhere on screen, and NOT_TOUCH_MODAL lets that same touch reach the
   *  app — so a tap or swipe both clear the result instantly and pass straight through. */
  @android.annotation.SuppressLint("ClickableViewAccessibility")
  private fun ensureTouchWatcher() {
    if (touchWatcher != null) return
    val watcher = View(service)
    watcher.setOnTouchListener { _, event ->
      if (event.action == MotionEvent.ACTION_OUTSIDE) {
        removeTranslationOverlays()
      }
      false
    }
    val params =
      WindowManager.LayoutParams(
        1,
        1,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
          WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
      )
    params.gravity = Gravity.TOP or Gravity.START
    params.windowAnimations = 0
    windowManager.addView(watcher, params)
    touchWatcher = watcher
  }

  private fun removeTouchWatcher() {
    touchWatcher?.let {
      try {
        windowManager.removeView(it)
      } catch (_: Exception) {
      }
    }
    touchWatcher = null
  }

  fun showCenteredLoading() {
    val container = FrameLayout(service)
    val bg = GradientDrawable()
    bg.setColor(Color.parseColor("#CC303030"))
    bg.cornerRadius = dpToPx(16).toFloat()
    container.background = bg

    val size = dpToPx(48)
    val progress = ProgressBar(service)
    val lp = FrameLayout.LayoutParams(size, size)
    lp.gravity = Gravity.CENTER
    val pad = dpToPx(16)
    container.setPadding(pad, pad, pad, pad)
    container.addView(progress, lp)

    val params =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
      )
    params.gravity = Gravity.CENTER

    windowManager.addView(container, params)
    translationOverlays.add(container)
  }

  fun showOverlayMessage(message: String) {
    val textView = TextView(service)
    textView.text = message
    textView.setTextColor(Color.WHITE)
    textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    val pad = dpToPx(16)
    textView.setPadding(pad, pad, pad, pad)
    val bg = GradientDrawable()
    bg.setColor(Color.parseColor("#DD333333"))
    bg.cornerRadius = dpToPx(8).toFloat()
    textView.background = bg

    val params =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
      )
    params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    params.y = dpToPx(80)

    windowManager.addView(textView, params)
    handler.postDelayed({
      try {
        windowManager.removeView(textView)
      } catch (_: Exception) {
      }
    }, 3000)
  }

  fun hasTranslationOverlays(): Boolean = translationOverlays.isNotEmpty()

  fun hasToolbar(): Boolean = toolbarView != null

  fun removeTranslationOverlays() {
    for (view in translationOverlays) {
      try {
        windowManager.removeView(view)
      } catch (_: Exception) {
      }
    }
    translationOverlays.clear()
    removeTouchWatcher()
  }

  fun getStatusBarHeight(): Int {
    val resourceId = service.resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (resourceId > 0) service.resources.getDimensionPixelSize(resourceId) else dpToPx(24)
  }

  fun getNavBarHeight(): Int {
    val resourceId = service.resources.getIdentifier("navigation_bar_height", "dimen", "android")
    return if (resourceId > 0) service.resources.getDimensionPixelSize(resourceId) else 0
  }

  fun showBorderWave() {
    if (borderView != null) return
    val view = BorderWaveView.create(service)
    val params =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
      )
    windowManager.addView(view, params)
    borderView = view
    view.startAnimation()
  }

  fun removeBorderWave() {
    borderView?.stopAnimation()
    borderView?.let {
      try {
        windowManager.removeView(it)
      } catch (_: Exception) {
      }
    }
    borderView = null
  }

  fun cleanup() {
    handler.removeCallbacksAndMessages(null)
    launcherBubble?.remove()
    launcherBubble = null
  }

  internal fun dpToPx(dp: Int): Int = (dp * service.resources.displayMetrics.density).toInt()
}
