package com.easycopy.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.graphics.ImageDecoder
import android.os.Build
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

data class OcrResult(
    val primary: String,
    val alternative: String?,
    val message: String
)

/**
 * Runs locally. Tesseract reads Arabic and English together; ML Kit is used as
 * an extra English recognizer, and its result can be selected from the UI.
 */
class OfflineOcr(private val context: Context) {
    fun recognize(uri: Uri): OcrResult {
        val bitmap = loadBitmap(uri)
        val enlarged = enlargeForText(bitmap)
        try {
            val latin = readLatin(enlarged)
            val multilingual = try { readMultilingual(enlarged) } catch (_: Exception) { "" }
            val containsArabic = Regex("[\\u0600-\\u06FF]").containsMatchIn(multilingual)
            val primary = when {
                containsArabic -> multilingual
                latin.isBlank() -> multilingual
                multilingual.isBlank() -> latin
                // For English-only text, ML Kit usually handles mobile UI fonts well.
                else -> latin
            }.trim()
            val alternative = when {
                primary == multilingual && latin.isNotBlank() && latin != multilingual -> latin
                primary == latin && multilingual.isNotBlank() && multilingual != latin -> multilingual
                else -> null
            }
            return OcrResult(
                primary,
                alternative,
                if (containsArabic) "تمت قراءة العربية والإنجليزية محليًا" else "اكتمل التعرف على النص محليًا"
            )
        } finally {
            if (enlarged !== bitmap) enlarged.recycle()
            bitmap.recycle()
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val sample = maxOf(1, maxOf(w, h) / 4000)
                if (sample > 1) decoder.setTargetSampleSize(sample)
            }
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        val ratio = maxOf(1, maxOf(options.outWidth, options.outHeight) / 4000)
        options.inSampleSize = ratio
        options.inJustDecodeBounds = false
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("Failed to decode image")
    }

    private fun enlargeForText(bitmap: Bitmap): Bitmap {
        val scale = minOf(
            2.0f,
            maxOf(1f, 2000f / bitmap.width),
            3800f / bitmap.height
        )
        if (scale < 1.12f) return bitmap
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun readLatin(bitmap: Bitmap): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                .text.trim()
        } catch (_: Exception) {
            ""
        } finally {
            recognizer.close()
        }
    }

    private fun readMultilingual(bitmap: Bitmap): String {
        val root = File(context.filesDir, "tesseract")
        val data = File(root, "tessdata")
        if (!data.exists() && !data.mkdirs()) return ""
        for (lang in listOf("ara", "eng")) {
            val filename = "$lang.traineddata"
            val destination = File(data, filename)
            if (!destination.exists() || destination.length() < 100_000L) {
                val temporary = File(data, "$filename.tmp")
                try {
                    context.assets.open("tessdata/$filename").use { input ->
                        FileOutputStream(temporary).use { output -> input.copyTo(output) }
                    }
                    if (!temporary.renameTo(destination)) {
                        temporary.copyTo(destination, overwrite = true)
                        temporary.delete()
                    }
                } finally {
                    if (temporary.exists()) temporary.delete()
                }
            }
        }
        val api = TessBaseAPI()
        try {
            if (!api.init(root.absolutePath, "ara+eng", TessBaseAPI.OEM_LSTM_ONLY)) return ""
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
            api.setImage(bitmap)
            return api.utF8Text.orEmpty().trim()
        } finally {
            api.recycle()
        }
    }
}
