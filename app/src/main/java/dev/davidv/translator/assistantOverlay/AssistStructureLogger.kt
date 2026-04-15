package dev.davidv.translator.assistantOverlay

import android.app.assist.AssistStructure
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi
import dev.davidv.translator.bounds

class AssistStructureLogger(
  private val tag: String,
  private val isDebuggable: Boolean,
  private val parser: AssistStructureParser,
) {
  @RequiresApi(Build.VERSION_CODES.M)
  fun dumpTree(structure: AssistStructure) {
    if (!isDebuggable) return
    val sb = StringBuilder()
    sb.appendLine("AssistStructure tree (windows=${structure.windowNodeCount})")
    for (windowIndex in 0 until structure.windowNodeCount) {
      val window = structure.getWindowNodeAt(windowIndex)
      sb.appendLine(
        "Window[$windowIndex] title=${window.title} bounds=[${window.left},${window.top},${window.left + window.width},${window.top + window.height}]",
      )
      dumpNode(sb, window.rootViewNode, window.left, window.top, depth = 1)
    }
    for (line in sb.lines()) {
      if (line.isNotEmpty()) Log.d(tag, line)
    }
  }

  @RequiresApi(Build.VERSION_CODES.M)
  private fun dumpNode(
    sb: StringBuilder,
    node: AssistStructure.ViewNode,
    baseLeft: Int,
    baseTop: Int,
    depth: Int,
  ) {
    val indent = "  ".repeat(depth)
    val left = baseLeft + node.left - node.scrollX
    val top = baseTop + node.top - node.scrollY
    val vis = visibilityName(node.visibility)
    sb.append(indent)
    sb.append(node.className ?: "?")
    if (node.idEntry != null) sb.append(" #${node.idEntry}")
    if (node.text != null) sb.append(" text=\"${node.text}\"")
    sb.append(" [$left,$top,${left + node.width},${top + node.height}]")
    if (vis != "VISIBLE") sb.append(" $vis")
    if (node.childCount > 0) sb.append(" children=${node.childCount}")
    sb.appendLine()
    for (childIndex in 0 until node.childCount) {
      val child = node.getChildAt(childIndex) ?: continue
      dumpNode(sb, child, left, top, depth + 1)
    }
  }

  @RequiresApi(Build.VERSION_CODES.M)
  fun log(structure: AssistStructure) {
    if (!isDebuggable) return
    Log.d(tag, "AssistStructure windows=${structure.windowNodeCount}")
    for (windowIndex in 0 until structure.windowNodeCount) {
      val window = structure.getWindowNodeAt(windowIndex)
      Log.d(
        tag,
        "Window[$windowIndex] left=${window.left} top=${window.top} width=${window.width} height=${window.height} title=${window.title}",
      )
      logViewNode(window.rootViewNode, window.left, window.top, depth = 0)
    }

    val parsedFragments = parser.parse(structure)
    Log.d(tag, "Parsed fragments: ${parsedFragments.size}")
    parsedFragments.forEachIndexed { index, fragment ->
      Log.d(
        tag,
        "Parsed[$index] text=${fragment.text} bounds=[${fragment.bounds.left},${fragment.bounds.top},${fragment.bounds.right},${fragment.bounds.bottom}] size=${fragment.style?.textSize} color=${fragment.style?.textColor} bg=${fragment.style?.bgColor} bold=${fragment.style?.bold} italic=${fragment.style?.italic}",
      )
    }
  }

  @RequiresApi(Build.VERSION_CODES.M)
  private fun logViewNode(
    node: AssistStructure.ViewNode,
    baseLeft: Int,
    baseTop: Int,
    depth: Int,
  ) {
    val indent = "  ".repeat(depth)
    val left = baseLeft + node.left - node.scrollX
    val top = baseTop + node.top - node.scrollY
    val bounds = Rect(left, top, left + node.width, top + node.height)
    val line =
      buildString {
        append(indent)
        append("node class=")
        append(node.className)
        append(" id=")
        append(node.idEntry)
        append(" text=")
        append(node.text)
        append(" hint=")
        append(node.hint)
        append(" desc=")
        append(node.contentDescription)
        append(" bounds=")
        append(bounds.toShortString())
        append(" size=")
        append(node.textSize)
        append(" color=")
        append(node.textColor)
        append(" bg=")
        append(node.textBackgroundColor)
        append(" style=")
        append(node.textStyle)
        append(" baselines=")
        append(node.textLineBaselines?.contentToString())
        append(" charOffsets=")
        append(node.textLineCharOffsets?.contentToString())
        append(" scroll=(")
        append(node.scrollX)
        append(",")
        append(node.scrollY)
        append(")")
        append(" transform=")
        append(node.transformation)
        append(" alpha=")
        append(node.alpha)
        append(" visibility=")
        append(visibilityName(node.visibility))
      }
    Log.d(tag, line)

    for (childIndex in 0 until node.childCount) {
      val child = node.getChildAt(childIndex) ?: continue
      logViewNode(child, left, top, depth + 1)
    }
  }

  private fun visibilityName(value: Int): String =
    when (value) {
      View.VISIBLE -> "VISIBLE"
      View.INVISIBLE -> "INVISIBLE"
      View.GONE -> "GONE"
      else -> value.toString()
    }
}
