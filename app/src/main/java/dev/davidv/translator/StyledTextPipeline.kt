package dev.davidv.translator

typealias TextStyle = uniffi.translator.TextStyle
typealias StyledFragment = uniffi.translator.StyledFragment

fun makeTextStyle(
  textColor: Int? = null,
  bgColor: Int? = null,
  textSize: Float? = null,
  bold: Boolean = false,
  italic: Boolean = false,
  underline: Boolean = false,
  strikethrough: Boolean = false,
): TextStyle =
  TextStyle(
    textColor = textColor?.toUInt(),
    bgColor = bgColor?.toUInt(),
    textSize = textSize,
    bold = bold,
    italic = italic,
    underline = underline,
    strikethrough = strikethrough,
  )

fun makeStyledFragment(
  text: String,
  bounds: Rect,
  style: TextStyle? = null,
  layoutGroup: Int = 0,
  translationGroup: Int = 0,
  clusterGroup: Int = 0,
): StyledFragment =
  StyledFragment(
    text = text,
    boundingBox = bounds.toUniffiRect(),
    style = style,
    layoutGroup = layoutGroup.toUInt(),
    translationGroup = translationGroup.toUInt(),
    clusterGroup = clusterGroup.toUInt(),
  )

val StyledFragment.bounds: Rect
  get() =
    Rect(
      boundingBox.left.toInt(),
      boundingBox.top.toInt(),
      boundingBox.right.toInt(),
      boundingBox.bottom.toInt(),
    )

fun TextStyle.hasRealBackground(): Boolean {
  val c = bgColor?.toInt() ?: return false
  if (c == 0 || c == 1 || c == -1) return false
  if (c ushr 24 == 0) return false
  return true
}

fun Rect.toUniffiRect(): uniffi.translator.Rect =
  uniffi.translator.Rect(
    left = left.coerceAtLeast(0).toUInt(),
    top = top.coerceAtLeast(0).toUInt(),
    right = right.coerceAtLeast(left.coerceAtLeast(0)).toUInt(),
    bottom = bottom.coerceAtLeast(top.coerceAtLeast(0)).toUInt(),
  )
