package com.example.automation

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScreenSnapshot(
    val packageName: String,
    val visibleLines: List<String>,
    val clickableTargets: List<String>,
    val editableFields: List<String>
) {
    val combinedText: String
        get() = visibleLines.joinToString("\n")
}

class CommanderAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        _activeService.value = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (!pkg.isNullOrBlank()) {
            _lastPackage.value = pkg
        }
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        if (_activeService.value === this) {
            _activeService.value = null
        }
        super.onDestroy()
    }

    fun captureScreenSnapshot(): ScreenSnapshot? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: _lastPackage.value
        val lines = mutableListOf<String>()
        val clickables = mutableListOf<String>()
        val editables = mutableListOf<String>()

        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 24 || lines.size > 140) return
            // Privacy guard: never extract password/OTP masked fields
            if (!node.isPassword) {
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                val hint = node.hintText?.toString()?.trim().orEmpty()
                val label = text.ifBlank { desc }
                if (label.isNotBlank() && label !in lines) {
                    lines.add(label)
                }
                if (node.isClickable && label.isNotBlank() && label !in clickables) {
                    clickables.add(label)
                }
                if (node.isEditable) {
                    val fieldName = hint.ifBlank { desc.ifBlank { "Text Field" } }
                    editables.add(fieldName)
                }
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i), depth + 1)
            }
        }

        traverse(root, 0)
        return ScreenSnapshot(
            packageName = pkg,
            visibleLines = lines,
            clickableTargets = clickables,
            editableFields = editables
        )
    }

    fun clickNodeByText(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val needle = target.trim().lowercase()
        if (needle.isBlank()) return false

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!node.isPassword) {
                val text = node.text?.toString()?.lowercase().orEmpty()
                val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
                if (text.contains(needle) || desc.contains(needle)) {
                    var current: AccessibilityNodeInfo? = node
                    while (current != null) {
                        if (current.isClickable) {
                            return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        current = current.parent
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    fun typeTextInField(targetHint: String?, textToType: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val needle = targetHint?.trim()?.lowercase().orEmpty()
        val editableNodes = mutableListOf<AccessibilityNodeInfo>()

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable && !node.isPassword) {
                editableNodes.add(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        val chosen = if (needle.isNotBlank()) {
            editableNodes.firstOrNull { node ->
                val hint = node.hintText?.toString()?.lowercase().orEmpty()
                val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
                val txt = node.text?.toString()?.lowercase().orEmpty()
                hint.contains(needle) || desc.contains(needle) || txt.contains(needle)
            } ?: editableNodes.firstOrNull { it.isFocused } ?: editableNodes.firstOrNull()
        } else {
            editableNodes.firstOrNull { it.isFocused } ?: editableNodes.firstOrNull()
        } ?: return false

        chosen.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
        }
        return chosen.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scrollWindow(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (direction.equals("up", ignoreCase = true)) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isScrollable) {
                if (node.performAction(action)) return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    fun navigateBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    companion object {
        private val _activeService = MutableStateFlow<CommanderAccessibilityService?>(null)
        val activeService: StateFlow<CommanderAccessibilityService?> = _activeService.asStateFlow()

        private val _lastPackage = MutableStateFlow("")
        val lastPackage: StateFlow<String> = _lastPackage.asStateFlow()

        fun isConnected(): Boolean = _activeService.value != null
    }
}
