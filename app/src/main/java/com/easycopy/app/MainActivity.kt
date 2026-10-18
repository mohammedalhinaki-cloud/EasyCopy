package com.easycopy.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(42), dp(24), dp(24))
            layoutDirection = LinearLayout.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.rgb(248, 250, 252))
        }
        fun paragraph(value: String, size: Float, color: Int, bottom: Int): TextView {
            val view = TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                gravity = Gravity.RIGHT
                setPadding(0, 0, 0, dp(bottom))
            }
            layout.addView(view, LinearLayout.LayoutParams(-1, -2))
            return view
        }
        paragraph("Easy Copy", 30f, Color.rgb(22, 48, 74), 18)
        status = paragraph("", 19f, Color.rgb(35, 100, 70), 20)
        paragraph(
            "انسخ النصوص القابلة للقراءة عبر خدمة إمكانية الوصول. بعد تفعيل الخدمة اضغط مطولًا على نص في أي تطبيق؛ عندما يسمح Android بالوصول إليه، تظهر نافذة تستطيع منها تحديد الجزء الذي تريده ونسخه.",
            16f, Color.DKGRAY, 25
        )
        val settingsButton = Button(this).apply {
            text = "تفعيل خدمة إمكانية الوصول"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        layout.addView(settingsButton, LinearLayout.LayoutParams(-1, -2))
        paragraph(
            "\nإذا لم يستجب تطبيق للضغط المطوّل، أضف اختصار «نسخ نص الشاشة» إلى الإعدادات السريعة؛ سيكون هذا بديلًا اختياريًا.",
            15f, Color.DKGRAY, 20
        )
        paragraph(
            "الخصوصية: لا صور شاشة ولا OCR ولا إنترنت ولا حفظ لسجل النصوص. لا يمكن نسخ نصوص التطبيقات التي تحجب بيانات إمكانية الوصول.",
            14f, Color.GRAY, 10
        )
        scroll.addView(layout)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        val expected = ComponentName(this, EasyCopyService::class.java)
        val enabled = Settings.Secure.getString(contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val active = enabled.split(':').any {
            ComponentName.unflattenFromString(it) == expected
        }
        status.text = if (active) "الخدمة مفعّلة" else "الخدمة غير مفعّلة"
        status.setTextColor(if (active) Color.rgb(0, 120, 80) else Color.rgb(170, 75, 20))
    }
}
