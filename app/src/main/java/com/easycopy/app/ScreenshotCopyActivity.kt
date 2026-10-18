package com.easycopy.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Share a screenshot into Easy Copy to extract text on-device.
 * The bundled ML Kit model recognizes Latin-script text; no photo is uploaded.
 */
class ScreenshotCopyActivity : Activity() {
    private lateinit var container: LinearLayout
    private lateinit var message: TextView
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = LinearLayout.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(30), dp(20), dp(24))
            setBackgroundColor(Color.rgb(249, 250, 252))
        }
        val title = TextView(this).apply {
            text = "Easy Copy — نسخ نص الصورة"
            textSize = 22f
            setTextColor(Color.rgb(25, 45, 65))
            setPadding(0, 0, 0, dp(18))
        }
        container.addView(title)
        message = TextView(this).apply {
            text = "جارٍ استخراج النص من الصورة داخل الجوال..."
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, dp(18))
        }
        container.addView(message)
        container.addView(ProgressBar(this))
        scroll.addView(container)
        setContentView(scroll)
        val imageUri = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        } ?: intent.clipData?.getItemAt(0)?.uri
        if (intent.action != Intent.ACTION_SEND ||
            intent.type?.startsWith("image/") != true || imageUri == null) {
            showError("لم تصل صورة صالحة للمشاركة")
            return
        }
        try {
            val input = InputImage.fromFilePath(this, imageUri)
            recognizer.process(input)
                .addOnSuccessListener { result ->
                    if (!isFinishing && !isDestroyed) showResult(result.text.trim())
                }
                .addOnFailureListener {
                    if (!isFinishing && !isDestroyed) showError("تعذّر قراءة النص من الصورة")
                }
        } catch (_: Exception) {
            showError("لا يمكن فتح الصورة. حاول مشاركتها من تطبيق المعرض.")
        }
    }

    private fun clearProgress() {
        if (container.childCount > 2) {
            container.removeViews(2, container.childCount - 2)
        }
    }

    private fun showError(reason: String) {
        clearProgress()
        message.text = reason
        val close = Button(this).apply {
            text = "إغلاق"
            setOnClickListener { finish() }
        }
        container.addView(close)
    }

    private fun copy(value: String) {
        if (value.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Easy Copy image text", value))
        Toast.makeText(this, "تم نسخ النص", Toast.LENGTH_SHORT).show()
    }

    private fun showResult(text: String) {
        clearProgress()
        if (text.isBlank()) {
            showError("لم يُعثر على نص مدعوم. يعمل التعرف الحالي مع الحروف اللاتينية مثل الإنجليزية، وليس العربية.")
            return
        }
        copy(text)
        message.text = "نُسخ النص تلقائيًا. يمكنك تحديد جزء منه أو تصحيح الأخطاء ثم نسخه مرة أخرى."
        val editor = EditText(this).apply {
            setText(text)
            setTextIsSelectable(true)
            gravity = Gravity.TOP or Gravity.START
            textSize = 16f
            minLines = 6
            maxLines = 20
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.BLACK)
        }
        container.addView(editor, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(label: String, onClick: () -> Unit) {
            row.addView(Button(this).apply {
                this.text = label
                textSize = 13f
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        action("نسخ المحدد") {
            val start = editor.selectionStart
            val end = editor.selectionEnd
            if (start != end && start >= 0 && end >= 0) {
                copy(editor.text.substring(minOf(start, end), maxOf(start, end)))
            } else {
                Toast.makeText(this, "حدّد جزءًا من النص أولًا", Toast.LENGTH_SHORT).show()
            }
        }
        action("نسخ الكل") { copy(editor.text.toString()) }
        action("إغلاق") { finish() }
        container.addView(row)
        val note = TextView(this).apply {
            text = "يعمل دون إنترنت؛ نتائج التعرف قد تحتوي أخطاء، وخصوصًا الأرقام والخطوط الصغيرة."
            textSize = 13f
            setTextColor(Color.GRAY)
        }
        container.addView(note)
    }

    override fun onDestroy() {
        if (::container.isInitialized) recognizer.close()
        super.onDestroy()
    }
}
