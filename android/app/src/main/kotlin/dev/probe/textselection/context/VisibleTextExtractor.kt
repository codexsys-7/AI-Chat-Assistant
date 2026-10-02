package dev.probe.textselection.context

import android.view.accessibility.AccessibilityNodeInfo

internal data class VisibleTextNode(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

internal object VisibleTextExtractor {
    const val MAX_NODES = 40

    fun collect(root: AccessibilityNodeInfo): List<VisibleTextNode> {
        val nodes = ArrayList<VisibleTextNode>(MAX_NODES)
        walk(root, nodes)
        return nodes
    }

    private fun walk(node: AccessibilityNodeInfo, out: MutableList<VisibleTextNode>) {
        if (out.size >= MAX_NODES) return
        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty() && node.isVisibleToUser) {
            out += VisibleTextNode(
                text = text,
                selectionStart = node.textSelectionStart,
                selectionEnd = node.textSelectionEnd,
            )
        }
        for (index in 0 until node.childCount) {
            if (out.size >= MAX_NODES) return
            val child = node.getChild(index) ?: continue
            try {
                walk(child, out)
            } finally {
                child.recycle()
            }
        }
    }
}
