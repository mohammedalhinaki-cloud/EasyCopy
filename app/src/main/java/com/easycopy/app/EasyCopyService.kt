package com.easycopy.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class EasyCopyService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var windowManager: WindowManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 80
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) return
        if (event.packageName?.toString() == packageName || event.isPassword) return
        val node = event.source ?: return
        if (node.isPassword) return
        // Leave native selection UI alone when the clicked text already supports it.
        if (node.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION ||
                it.id == AccessibilityNodeInfo.ACTION_COPY
            }) return
        val extracted = readText(node, 0)
        if (extracted.isNotBlank()) displayText(extracted)
    }

    private fun readText(node: AccessibilityNodeInfo?, depth: Int): String {
        if (node == null || depth > 15 || node.isPassword) return ""
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) return text.take(10000)
        val parts = ArrayList<String>()
        for (i in 0 until minOf(node.childCount, 60)) {
            val child = node.getChild(i) ?: continue
            val value = readText(child, depth + 1)
            if (value.isNotBlank()) parts.add(value)
        }
        if (parts.isNotEmpty()) return parts.distinct().joinToString("\n").take(10000)
        return node.contentDescription?.toString()?.trim()?.take(10000).orEmpty()
    }

    fun copyFromScreen() {
        val root = rootInActiveWindow
        val text = readText(root, 0)
        if (text.isBlank()) {
            Toast.makeText(this, "التطبيق لا يتيح قراءة النص", Toast.LENGTH_SHORT).show()
        } else displayText(text)
    }

    private fun copy(value: String) {
        if (value.isEmpty()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Easy Copy", value))
        Toast.makeText(this, "تم النسخ", Toast.LENGTH_SHORT).show()
    }

    private fun displayText(text: String) = handler.post {
        closeOverlay()
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = resources.displayMetrics.density
            fun dp(n: Int) = (density * n).toInt()
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(16))
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(20).toFloat()
                }
                elevation = dp(8).toFloat()
            }
            val title = TextView(this).apply {
                this.text = "تحديد النص ونسخه"
                textSize = 18f
                setTextColor(Color.rgb(27, 46, 66))
                setPadding(0, 0, 0, dp(12))
            }
            container.addView(title)
            val edit = EditText(this).apply {
                setText(text.take(10000))
                setTextIsSelectable(true)
                minLines = 2
                maxLines = 9
                textSize = 16f
                setTextColor(Color.BLACK)
                setBackgroundColor(Color.rgb(245, 248, 251))
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            container.addView(edit, LinearLayout.LayoutParams(-1, -2))
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            }
            fun action(label: String, callback: () -> Unit) {
                actions.addView(Button(this).apply {
                    this.text = label
                    setOnClickListener { callback() }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            action("نسخ المحدد") {
                val a = edit.selectionStart
                val b = edit.selectionEnd
                if (a >= 0 && b >= 0 && a != b) {
                    copy(edit.text.substring(minOf(a, b), maxOf(a, b)))
                    closeOverlay()
                } else {
                    Toast.makeText(this, "حدّد النص أولًا", Toast.LENGTH_SHORT).show()
                }
            }
            action("نسخ الكل") {
                copy(edit.text.toString())
                closeOverlay()
            }
            action("إغلاق") { closeOverlay() }
            container.addView(actions)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
                windowAnimations = android.R.style.Animation_Dialog
            }
            wm.addView(container, params)
            overlay = container
            windowManager = wm
        } catch (_: Exception) {
            closeOverlay()
        }
    }

    private fun closeOverlay() {
        overlay?.let { view ->
            try { windowManager?.removeViewImmediate(view) } catch (_: Exception) {}
        }
        overlay = null
        windowManager = null
    }

    override fun onInterrupt() { closeOverlay() }
    override fun onDestroy() {
        closeOverlay()
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        @Volatile var current: EasyCopyService? = null
            private set
    }
}
