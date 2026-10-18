package com.easycopy.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Receives a text share and copies only the episode title.
 * Pocket Casts sends the title in Intent.EXTRA_TITLE and the URL in EXTRA_TEXT.
 * When only a URL is provided, never claim that the URL is the title.
 */
class CopyEpisodeTitleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action != Intent.ACTION_SEND || intent?.type != "text/plain") {
            finish()
            return
        }

        val shareTitle = intent.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString()
        val subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()
        val body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()

        fun titleLike(text: String?): String? {
            val value = text?.trim().orEmpty()
            if (value.isEmpty() || value.startsWith("https://", ignoreCase = true) ||
                value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("www.", ignoreCase = true)) return null
            return value.take(10000)
        }

        val episodeTitle = titleLike(shareTitle)
            ?: titleLike(subject)
            ?: body.lines().firstNotNullOfOrNull { titleLike(it) }

        if (episodeTitle == null) {
            Toast.makeText(
                this,
                "وصل رابط فقط دون اسم الحلقة؛ جرّب خيار نسخ نص الشاشة",
                Toast.LENGTH_LONG
            ).show()
        } else {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Episode title", episodeTitle))
            Toast.makeText(this, "تم نسخ اسم الحلقة", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
