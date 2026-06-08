package dev.davidv.translator.assistantOverlay

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageStateManager
import dev.davidv.translator.MainActivity
import dev.davidv.translator.OverlayTextTranslationHelper
import dev.davidv.translator.R
import dev.davidv.translator.ReadingOrder
import dev.davidv.translator.SettingsManager
import dev.davidv.translator.TranslationCoordinator
import dev.davidv.translator.overlayChrome.OverlayChromeFactory
import dev.davidv.translator.overlayChrome.OverlayMenuHost
import dev.davidv.translator.overlayChrome.OverlayMenuManager
import dev.davidv.translator.screenTranslate.ScreenCaptureRequestActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TranslatorVoiceInteractionSession(
  context: Context,
  private val settingsManager: SettingsManager,
  private val translationCoordinator: TranslationCoordinator,
  private val overlayTextTranslationHelper: OverlayTextTranslationHelper,
  private val langStateManager: LanguageStateManager,
) : VoiceInteractionSession(context) {
  companion object {
    private const val ASSIST_SCREENSHOT_ENABLED_SETTING = "assist_screenshot_enabled"
    private const val CAPTURE_TIMEOUT_MS = 1500L
  }

  private val tag = "TranslatorAssistant"
  private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  private lateinit var rootView: FrameLayout
  private lateinit var screenshotView: ImageView
  private lateinit var overlayContainer: FrameLayout
  private lateinit var statusView: TextView
  private lateinit var loadingView: View
  private lateinit var topBarView: View
  private var sourceLabelView: TextView? = null
  private var targetLabelView: TextView? = null
  private var readingOrderButtonView: View? = null
  private var readingOrderIconView: ImageView? = null
  private var menuManager: OverlayMenuManager? = null
  private var borderView: BorderWaveView? = null

  private val systemBarTop: Int by lazy {
    val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
    if (id > 0) context.resources.getDimensionPixelSize(id) else 0
  }

  private val systemBarBottom: Int by lazy {
    val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
    if (id > 0) context.resources.getDimensionPixelSize(id) else 0
  }

  private var screenshotBitmap: Bitmap? = null
  private var croppedBitmap: Bitmap? = null
  private var processing = false
  private var translationJob: Job? = null
  private var captureTimeoutJob: Job? = null
  private var statusHideJob: Job? = null
  private var forcedSourceLanguage: Language? = null
  private var isAutoSource: Boolean = true
  private var forcedTargetLanguage: Language? = null
  private var ocrReadingOrder: ReadingOrder? = null
  private var lastOriginalText: String = ""
  private var lastTranslatedText: String = ""

  override fun onCreate() {
    super.onCreate()
    configureSessionWindow()
    langStateManager.refreshLanguageAvailability()
  }

  override fun onCreateContentView(): View {
    rootView = FrameLayout(context)
    rootView.setBackgroundColor(Color.TRANSPARENT)
    rootView.setOnApplyWindowInsetsListener { _, insets -> insets }
    menuManager =
      OverlayMenuManager(
        context,
        ::dpToPx,
        object : OverlayMenuHost {
          override fun addDismissLayer(view: View) {
            rootView.addView(
              view,
              FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
              ),
            )
          }

          override fun addMenuView(view: View) {
            rootView.addView(
              view,
              FrameLayout
                .LayoutParams(
                  dpToPx(180),
                  FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                  gravity = Gravity.TOP or Gravity.END
                  topMargin = dpToPx(48)
                  marginEnd = dpToPx(8)
                },
            )
          }

          override fun addPickerView(view: View) {
            rootView.addView(
              view,
              FrameLayout
                .LayoutParams(
                  dpToPx(250),
                  dpToPx(400),
                ).apply { gravity = Gravity.CENTER },
            )
          }

          override fun removeMenuChild(view: View) {
            rootView.removeView(view)
          }
        },
      )

    screenshotView =
      ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_START
        setBackgroundColor(Color.TRANSPARENT)
      }
    rootView.addView(
      screenshotView,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
      ),
    )

    overlayContainer = FrameLayout(context)
    rootView.addView(
      overlayContainer,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
      ),
    )

    borderView = BorderWaveView.create(context)
    rootView.addView(
      borderView,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
      ),
    )

    topBarView = buildTopBar()
    rootView.addView(
      topBarView,
      FrameLayout
        .LayoutParams(
          FrameLayout.LayoutParams.MATCH_PARENT,
          FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
          gravity = Gravity.TOP or Gravity.START
        },
    )

    statusView =
      TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setPadding(dpToPx(20), dpToPx(12), dpToPx(20), dpToPx(12))
        background =
          GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(12).toFloat()
            setColor(Color.parseColor("#CC202020"))
          }
      }
    val statusParams =
      FrameLayout
        .LayoutParams(
          FrameLayout.LayoutParams.WRAP_CONTENT,
          FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
          gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
          bottomMargin = systemBarBottom + dpToPx(24)
        }
    rootView.addView(statusView, statusParams)

    loadingView = buildLoadingView()
    rootView.addView(
      loadingView,
      FrameLayout
        .LayoutParams(
          FrameLayout.LayoutParams.WRAP_CONTENT,
          FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER },
    )

    showStatus("Invoke this assistant on top of text to translate it")
    showLoading(false)
    updateBackdrop()
    return rootView
  }

  override fun onShow(
    args: Bundle?,
    showFlags: Int,
  ) {
    super.onShow(args, showFlags)
    configureSessionWindow()
    clearCapture()
    overlayContainer.removeAllViews()
    dismissMenu()
    updateBackdrop()

    if (settingsManager.settings.value.assistantAction == dev.davidv.translator.AssistantAction.LIVE_SCREEN) {
      launchLiveScreen()
      return
    }

    showStatus("Collecting screen context...")
    showLoading(true)
    startBorderPulse()

    if (!isAssistScreenshotEnabled()) {
      Log.w(tag, "Assistant screenshot capture is disabled in system settings")
      showLoading(false)
      stopBorderPulse()
      showStatus("Screenshot access is disabled for this assistant. Enable it in the system assistant settings.")
      return
    }

    captureTimeoutJob =
      sessionScope.launch {
        delay(CAPTURE_TIMEOUT_MS)
        if (screenshotBitmap == null && !processing) {
          showLoading(false)
          stopBorderPulse()
          showStatus("No screen data received. Enable 'Use screenshot' for this assistant.")
        }
      }
  }

  override fun onComputeInsets(outInsets: Insets) {
    outInsets.contentInsets.set(0, 0, 0, 0)
    outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
  }

  override fun onHide() {
    super.onHide()
    stopBorderPulse()
    clearCapture()
    overlayContainer.removeAllViews()
    dismissMenu()
    screenshotView.setImageDrawable(null)
    updateBackdrop()
    showLoading(false)
  }

  override fun onDestroy() {
    stopBorderPulse()
    sessionScope.cancel()
    clearCapture()
    super.onDestroy()
  }

  override fun onHandleScreenshot(screenshot: Bitmap?) {
    super.onHandleScreenshot(screenshot)
    captureTimeoutJob?.cancel()
    captureTimeoutJob = null
    Log.d(tag, "Screenshot callback received bitmap=${screenshot?.width}x${screenshot?.height}")
    if (screenshot == null) {
      showLoading(false)
      stopBorderPulse()
      showStatus("This app did not provide a screenshot.")
      return
    }
    val oldSs = screenshotBitmap
    val oldCr = croppedBitmap
    oldSs?.recycle()
    if (oldCr != null && oldCr !== oldSs) oldCr.recycle()
    screenshotBitmap = screenshot.copy(Bitmap.Config.ARGB_8888, false)
    croppedBitmap = screenshotBitmap?.let { cropSystemBars(it) }
    screenshotView.setImageBitmap(croppedBitmap)
    updateBackdrop()
    runFullScreenOcr()
  }

  private fun runFullScreenOcr() {
    val screenshot = screenshotBitmap ?: return
    overlayContainer.removeAllViews()
    showLoading(true)
    hideStatus()
    val sourceLanguage = ocrSourceLanguage()
    if (!isAutoSource && sourceLanguage == null) {
      processing = false
      showLoading(false)
      showStatus("Set a default source language for OCR.")
      return
    }

    val targetLanguage = forcedTargetLanguage ?: langStateManager.languageByCode(settingsManager.settings.value.defaultTargetLanguageCode) ?: return
    val ocrSourceLanguage = sourceLanguage ?: targetLanguage
    val cropped = cropSystemBars(screenshot)
    val workingBitmap = cropped.copy(Bitmap.Config.ARGB_8888, false)
    if (cropped !== screenshot) cropped.recycle()
    processing = true
    translationJob =
      sessionScope.launch {
        val result =
          withContext(Dispatchers.IO) {
            translationCoordinator.translateImageWithOverlay(
              ocrSourceLanguage,
              targetLanguage,
              workingBitmap,
              onMessage = {},
              readingOrder = currentReadingOrderFor(sourceLanguage),
              isAutoSource = isAutoSource,
            )
          }
        ensureActive()
        processing = false
        showLoading(false)
        if (result == null) {
          showStatus("OCR failed")
          return@launch
        }
        lastOriginalText = result.extractedText
        lastTranslatedText = result.translatedText
        val oldCropped = croppedBitmap
        if (oldCropped != null && oldCropped !== screenshotBitmap) oldCropped.recycle()
        croppedBitmap = result.correctedBitmap
        screenshotView.setImageBitmap(croppedBitmap)
        updateBackdrop()
        hideStatus()
      }
  }

  private fun startBorderPulse() {
    borderView?.startAnimation()
  }

  private fun stopBorderPulse() {
    borderView?.stopAnimation()
  }

  private fun buildLoadingView(): View {
    val container =
      FrameLayout(context).apply {
        background =
          GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(16).toFloat()
            setColor(Color.parseColor("#CC202020"))
          }
        val padding = dpToPx(16)
        setPadding(padding, padding, padding, padding)
      }

    val progress = ProgressBar(context)
    container.addView(
      progress,
      FrameLayout
        .LayoutParams(
          dpToPx(48),
          dpToPx(48),
        ).apply { gravity = Gravity.CENTER },
    )
    return container
  }

  @Suppress("DEPRECATION")
  private fun configureSessionWindow() {
    val dialog = window ?: return
    val win = dialog.window ?: return
    win.setLayout(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.MATCH_PARENT,
    )
    win.setGravity(Gravity.TOP or Gravity.START)
    win.setBackgroundDrawable(null)
    win.decorView.setBackgroundColor(Color.TRANSPARENT)
    win.decorView.setPadding(0, 0, 0, 0)
    win.setWindowAnimations(0)
    val contentFrame = win.decorView.findViewById<View>(android.R.id.content)
    contentFrame?.setBackgroundColor(Color.TRANSPARENT)
    (contentFrame?.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
  }

  private fun showStatus(
    message: String,
    autoHideAfterMs: Long? = null,
  ) {
    if (!::statusView.isInitialized) return
    statusHideJob?.cancel()
    statusHideJob = null
    statusView.text = message
    statusView.visibility = View.VISIBLE
    if (autoHideAfterMs != null) {
      statusHideJob =
        sessionScope.launch {
          delay(autoHideAfterMs)
          hideStatus()
        }
    }
  }

  private fun hideStatus() {
    if (!::statusView.isInitialized) return
    statusHideJob?.cancel()
    statusHideJob = null
    statusView.visibility = View.GONE
  }

  private fun showLoading(visible: Boolean) {
    if (!::loadingView.isInitialized) return
    loadingView.visibility = if (visible) View.VISIBLE else View.GONE
  }

  private fun cropSystemBars(source: Bitmap): Bitmap {
    val top = systemBarTop.coerceIn(0, source.height - 1)
    if (top == 0) return source
    return Bitmap.createBitmap(source, 0, top, source.width, source.height - top)
  }

  private fun clearCapture() {
    translationJob?.cancel()
    translationJob = null
    captureTimeoutJob?.cancel()
    captureTimeoutJob = null
    statusHideJob?.cancel()
    statusHideJob = null
    val ss = screenshotBitmap
    val cr = croppedBitmap
    ss?.recycle()
    if (cr != null && cr !== ss) cr.recycle()
    screenshotBitmap = null
    croppedBitmap = null
    processing = false
    lastOriginalText = ""
    lastTranslatedText = ""
  }

  private fun dpToPx(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()

  private fun updateBackdrop() {
    rootView.setBackgroundColor(Color.TRANSPARENT)
    screenshotView.setBackgroundColor(Color.TRANSPARENT)
  }

  private fun isAssistScreenshotEnabled(): Boolean =
    Settings.Secure.getInt(context.contentResolver, ASSIST_SCREENSHOT_ENABLED_SETTING, 1) != 0

  private fun ocrSourceLanguage(): Language? =
    forcedSourceLanguage ?: settingsManager.settings.value.defaultSourceLanguageCode?.let {
      langStateManager.languageByCode(it)
    }

  private fun buildTopBar(): View {
    val toolbarViews =
      OverlayChromeFactory.createLanguageToolbar(
        context = context,
        dpToPx = ::dpToPx,
        forcedSourceLanguage = forcedSourceLanguage,
        forcedTargetLanguage = forcedTargetLanguage,
        defaultTargetLanguage = langStateManager.languageByCode(settingsManager.settings.value.defaultTargetLanguageCode) ?: langStateManager.languageByCode("en")!!,
        onClose = { hide() },
        onTranslateScreenClick = { startScreenTranslate() },
        onSourceClick = { showLanguagePicker(true) },
        onSwap = { swapLanguages() },
        onTargetClick = { showLanguagePicker(false) },
        showReadingOrderButton = forcedSourceLanguage?.code == "ja",
        readingOrder = currentReadingOrderFor(forcedSourceLanguage),
        onReadingOrderClick = { toggleJapaneseOcrMode() },
        onMenuClick = { showDotsMenu() },
        isAutoSource = isAutoSource,
      )
    sourceLabelView = toolbarViews.sourceLabel
    targetLabelView = toolbarViews.targetLabel
    readingOrderButtonView = toolbarViews.readingOrderButton
    readingOrderIconView = toolbarViews.readingOrderIcon
    return toolbarViews.root
  }

  /** Live screen translate set as the assistant-invocation action: skip the still
   *  overlay entirely, launch the consent flow with the default languages, and
   *  dismiss this session. Needs a default source language (live can't run the
   *  per-frame script classifier). */
  private fun launchLiveScreen() {
    val settings = settingsManager.settings.value
    val source = settings.defaultSourceLanguageCode?.let { langStateManager.languageByCode(it) }
    if (source == null) {
      showLoading(false)
      stopBorderPulse()
      showStatus("Set a default source language to use live screen translation.")
      return
    }
    val targetCode = langStateManager.languageByCode(settings.defaultTargetLanguageCode)?.code
    runCatching {
      context.startActivity(
        ScreenCaptureRequestActivity.intent(context, source.code, targetCode, false),
      )
    }.onFailure { Log.w(tag, "failed to launch live screen translate", it) }
    hide()
  }

  /** Hand off to the live MediaProjection screen-translate experience from the
   *  still overlay's go-live button: launch the consent flow (overlay grant +
   *  capture token), which starts [ScreenCaptureRequestActivity]'s service, then
   *  dismiss this session so the frozen screenshot is removed and the real screen
   *  shows through for capture. */
  private fun startScreenTranslate() {
    // Live screen translate needs a fixed source language — gate it off in auto
    // mode and tell the user to pick one. A Toast doesn't surface from the
    // assistant's window, so use the overlay's own status line.
    if (isAutoSource || forcedSourceLanguage == null) {
      showStatus(context.getString(R.string.screen_translate_needs_source), autoHideAfterMs = 3000)
      return
    }
    val targetCode =
      (
        forcedTargetLanguage
          ?: langStateManager.languageByCode(settingsManager.settings.value.defaultTargetLanguageCode)
      )?.code
    val sourceCode = forcedSourceLanguage?.code
    runCatching {
      context.startActivity(
        ScreenCaptureRequestActivity.intent(
          context,
          sourceCode,
          targetCode,
          isAutoSource,
        ),
      )
    }.onFailure { Log.w(tag, "failed to launch screen-translate consent", it) }
    hide()
  }

  private fun swapLanguages() {
    val oldSource = forcedSourceLanguage
    val oldTarget = forcedTargetLanguage ?: langStateManager.languageByCode(settingsManager.settings.value.defaultTargetLanguageCode) ?: return
    isAutoSource = false
    forcedSourceLanguage = oldTarget
    forcedTargetLanguage = oldSource
    syncReadingOrderForSource()
    updateToolbarLabels()
    retranslate()
  }

  private fun retranslate() {
    translationJob?.cancel()
    translationJob = null
    processing = false
    if (screenshotBitmap != null) {
      runFullScreenOcr()
    } else {
      overlayContainer.removeAllViews()
      showLoading(true)
      hideStatus()
    }
  }

  private fun updateToolbarLabels() {
    sourceLabelView?.text = OverlayChromeFactory.formatSourceLabel(forcedSourceLanguage, isAutoSource)
    val currentTarget = forcedTargetLanguage ?: langStateManager.languageByCode(settingsManager.settings.value.defaultTargetLanguageCode)
    targetLabelView?.text = currentTarget?.shortDisplayName ?: "?"
    OverlayChromeFactory.updateReadingOrderButtonState(
      readingButton = readingOrderButtonView,
      readingIcon = readingOrderIconView,
      visible = forcedSourceLanguage?.code == "ja",
      readingOrder = currentReadingOrderFor(forcedSourceLanguage),
    )
  }

  private fun showLanguagePicker(isSource: Boolean) {
    sessionScope.launch {
      langStateManager.refreshLanguageAvailability()
      val availableLangs = overlayTextTranslationHelper.awaitAvailableLanguages(isSource)
      menuManager?.showLanguagePicker(
        isSource = isSource,
        availableLangs = availableLangs,
      ) { language ->
        if (isSource) {
          if (language == null) {
            isAutoSource = true
          } else {
            isAutoSource = false
            forcedSourceLanguage = language
          }
          syncReadingOrderForSource()
        } else {
          forcedTargetLanguage = language
        }
        updateToolbarLabels()
        retranslate()
      }
    }
  }

  private fun toggleJapaneseOcrMode() {
    if (forcedSourceLanguage?.code != "ja") return
    ocrReadingOrder =
      when (ocrReadingOrder) {
        null -> ReadingOrder.TOP_TO_BOTTOM_RIGHT_TO_LEFT
        ReadingOrder.TOP_TO_BOTTOM_RIGHT_TO_LEFT -> ReadingOrder.LEFT_TO_RIGHT
        ReadingOrder.LEFT_TO_RIGHT -> null
      }
    updateToolbarLabels()
    retranslate()
  }

  private fun currentReadingOrderFor(language: Language?): ReadingOrder? =
    if (language?.code == "ja") {
      ocrReadingOrder
    } else {
      null
    }

  private fun syncReadingOrderForSource() {
    if (forcedSourceLanguage?.code != "ja") {
      ocrReadingOrder = null
    }
  }

  private fun showDotsMenu() {
    menuManager?.showDotsMenu(
      listOf(
        "Copy original text" to { copyToClipboard("Original text", lastOriginalText) },
        "Copy translated text" to { copyToClipboard("Translated text", lastTranslatedText) },
        "Open App" to { openMainApp() },
      ),
    )
  }

  private fun copyToClipboard(
    label: String,
    text: String,
  ) {
    if (text.isBlank()) {
      showStatus("Nothing to copy yet", autoHideAfterMs = 2000)
      return
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    showStatus("Copied $label", autoHideAfterMs = 2000)
  }

  private fun openMainApp() {
    startAssistantActivity(Intent(context, MainActivity::class.java))
    hide()
  }

  private fun dismissMenu() {
    menuManager?.dismiss()
  }
}
