package dev.davidv.translator.overlayChrome

import android.content.res.Resources
import android.graphics.Rect
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager
import androidx.annotation.RequiresApi

object OverlayInsets {
  /** Top offset that clears both the status bar and any display cutout. The
   *  status_bar_height resource alone undershoots on notched devices where the
   *  cutout is taller than the reported status bar. */
  fun topInset(
    windowManager: WindowManager,
    resources: Resources,
  ): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      return windowManager.currentWindowMetrics.windowInsets
        .getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
        .top
    }
    val cutoutTop =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.cutout?.safeInsetTop ?: 0
      } else {
        0
      }
    return maxOf(statusBarHeight(resources), cutoutTop)
  }

  @RequiresApi(Build.VERSION_CODES.R)
  fun contentBounds(windowManager: WindowManager): Rect {
    val metrics = windowManager.currentWindowMetrics
    val insets =
      metrics.windowInsets.getInsets(
        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
      )
    return Rect(
      metrics.bounds.left + insets.left,
      metrics.bounds.top + insets.top,
      metrics.bounds.right - insets.right,
      metrics.bounds.bottom - insets.bottom,
    )
  }

  fun statusBarHeight(resources: Resources): Int {
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) {
      resources.getDimensionPixelSize(id)
    } else {
      (24 * resources.displayMetrics.density).toInt()
    }
  }
}

fun WindowManager.LayoutParams.spanFullDisplay() {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
  }
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    setFitInsetsTypes(0)
  }
}
