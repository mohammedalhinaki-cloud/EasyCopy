package com.easycopy.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
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
    private var lastMetaClick = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 80
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.isPassword) return
        if (event.packageName?.toString() == packageName) return
        val isClick = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
        if (!isClick && event.eventType != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) return
        val node = event.source
        if (node?.isPassword == true || node?.isEditable == true) return
        if (isClick) {
            // Never change taps in ordinary chats. Meta AI chat is opt-in by context.
            if (!isMetaAiWhatsAppChat()) return
            if (overlay != null || SystemClock.uptimeMillis() - lastMetaClick < 900L) return
            val rect = Rect()
            node?.getBoundsInScreen(rect)
            val height = resources.displayMetrics.heightPixels
            if (node == null || rect.isEmpty || rect.centerY() < height * 0.21f ||
                rect.centerY() > height * 0.85f) return
        }
        // Let normal Android selection and copy keep working.
        if (node?.actionList?.any {
                it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION ||
                it.id == AccessibilityNodeInfo.ACTION_COPY
            } == true) return
        val extracted = readText(node, 0).ifBlank {
            event.text.joinToString(" ").trim()
        }.ifBlank { readText(node?.parent, 0) }
        if (extracted.isNotBlank() && extracted.length > 2) {
            if (isClick) lastMetaClick = SystemClock.uptimeMillis()
            displayText(extracted)
        }
    }

    private fun isMetaAiWhatsAppChat(): Boolean {
        val root = rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return false
        val maxHeaderY = resources.displayMetrics.heightPixels * 0.23f
        fun hasHeader(node: AccessibilityNodeInfo?, depth: Int): Boolean {
            if (node == null || depth > 14) return false
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.top > maxHeaderY) return false
            val name = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val matches = listOf(name, desc).any {
                it.equals("Meta AI", ignoreCase = true) ||
                    it.startsWith("Meta AI,", ignoreCase = true) ||
                    it.startsWith("Meta AI،", ignoreCase = true)
            }
            if (matches && bounds.height() > 0) return true
            for (index in 0 until minOf(node.childCount, 70)) {
                if (hasHeader(node.getChild(index), depth + 1)) return true
            }
            return false
        }
        return hasHeader(root, 0)
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
        // When opened through Quick Settings, the system shade can obscure the source.
        val shownPackage = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (shownPackage.contains("systemui", ignoreCase = true)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed({ showScreenText() }, 550L)
        } else {
            showScreenText()
        }
    }

    private fun showScreenText() {
        val lines = LinkedHashSet<String>()
        var visited = 0
        var totalLength = 0

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 28 || visited >= 3000 || totalLength >= 30000) return
            visited++
            if (node.isPassword || node.isEditable) return

            val value = node.text?.toString()?.trim().orEmpty()
            if (value.isNotBlank()) {
                val v = value.take(12000)
                if (lines.add(v)) totalLength += v.length
            } else if (node.childCount == 0) {
                // Custom views may expose text only as an accessibility label.
                val description = node.contentDescription?.toString()?.trim().orEmpty()
                if (description.isNotBlank()) {
                    val v = description.take(12000)
                    if (lines.add(v)) totalLength += v.length
                }
            }
            for (i in 0 until minOf(node.childCount, 150)) {
                if (totalLength >= 30000 || visited >= 3000) break
                walk(node.getChild(i), depth + 1)
            }
        }

        val root = rootInActiveWindow
        if (root?.packageName?.toString()?.contains("systemui", ignoreCase = true) == true) {
            Toast.makeText(this, "أغلق شريط الإشعارات ثم أعد المحاولة", Toast.LENGTH_SHORT).show()
            return
        }
        walk(root, 0)
        if (lines.isEmpty()) {
            Toast.makeText(this, "لا توجد نصوص مكشوفة لإمكانية الوصول في هذه الشاشة", Toast.LENGTH_LONG).show()
        } else {
            displayText(lines.joinToString("\n").take(30000))
        }
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
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
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
            val paste = Button(this).apply {
                this.text = "لصق"
                textSize = 13f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(dp(8), 0, dp(8), 0)
                contentDescription = "لصق النص المحفوظ في موضع المؤشر"
                setOnClickListener {
                    try {
                        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                        val value = clip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(this@EasyCopyService)?.toString()
                            .orEmpty()
                        if (value.isBlank()) {
                            Toast.makeText(this@EasyCopyService, "الحافظة فارغة", Toast.LENGTH_SHORT).show()
                        } else {
                            val start = edit.selectionStart.coerceAtLeast(0)
                            val end = edit.selectionEnd.coerceAtLeast(0)
                            val from = minOf(start, end).coerceAtMost(edit.length())
                            val to = maxOf(start, end).coerceAtMost(edit.length())
                            edit.text.replace(from, to, value)
                            edit.setSelection((from + value.length).coerceAtMost(edit.length()))
                        }
                    } catch (_: SecurityException) {
                        Toast.makeText(this@EasyCopyService, "تعذّر الوصول للحافظة", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            header.addView(paste, LinearLayout.LayoutParams(-2, dp(40)))
            container.addView(header)
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
