package com.easycopy.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class OcrResult(
    val primary: String,
    val alternative: String?,
    val message: String,
    val choices: List<Pair<String, String>> = emptyList()
)

private data class OcrLine(
    val value: String,
    val bounds: Rect,
    val confidence: Float,
    val origin: String
)

private data class TessReading(val lines: List<OcrLine>, val confidence: Float) {
    val text: String get() = lines.joinToString("\n") { it.value }.trim()
}

/**
 * All OCR happens on-device, without any HTTP calls or runtime model downloads.
 *
 * ML Kit is good at Latin text but has no Arabic model. Tesseract is run in
 * both Arabic-only and Arabic+English modes and provides per-line boxes and
 * confidence. We merge by *position*, not by concatenating two whole pages.
 */
class OfflineOcr(private val context: Context) {
    private val arabic = Regex("[\u0621-\u064A\u0671-\u06D3]")
    private val latin = Regex("[A-Za-z]")

    fun recognize(uri: Uri): OcrResult {
        val original = loadBitmap(uri)
        val scaled = enlargeForText(original)
        val cleaned = improveContrast(scaled)
        try {
            val english = readLatin(scaled)
            val dataFolder = setupTessdata()
            // Script-specific models are much more reliable than mixing Arabic
            // and English into one Tesseract recognition run.
            val script = readTesseract(dataFolder, scaled, "Arabic", TessBaseAPI.PageSegMode.PSM_AUTO, "script")
            val arabicOnly = readTesseract(dataFolder, scaled, "ara", TessBaseAPI.PageSegMode.PSM_AUTO, "arabic")
            val bands = locateTextBands(scaled)
            val arabicLines = fillMissingLines(dataFolder, scaled, bands, script.lines, arabicOnly.lines)
            val mixed = TessReading(arabicLines, script.confidence)
            // Keep ML Kit Latin text ONLY if it lives in a different visual line;
            // do not append invented English words over Arabic glyphs.
            val joined = joinByPosition(english, arabicLines, emptyList())
            val latinText = formatLines(english)
            val mixedText = formatLines(arabicLines)
            val arabicText = formatLines(arabicOnly.lines)
            val combinedText = joined.trim()
            val hasArabic = combinedText.count { isArabic(it) } >= 2 ||
                arabicText.count { isArabic(it) } >= 4

            val mainText = when {
                hasArabic && mixedText.isNotBlank() -> mixedText
                latinText.isNotBlank() -> latinText
                combinedText.isNotBlank() -> combinedText
                mixedText.isNotBlank() -> mixedText
                else -> arabicText
            }
            val choices = linkedMapOf<String, String>()
            if (mainText.isNotBlank()) choices["أفضل قراءة (تلقائية)"] = mainText
            if (combinedText.isNotBlank() && combinedText != mainText) choices["دمج العربية والإنجليزية"] = combinedText
            if (arabicText.isNotBlank() && arabicText != mainText) choices["قراءة عربية"] = arabicText
            if (latinText.isNotBlank() && latinText != mainText) choices["قراءة إنجليزية"] = latinText
            if (mixedText.isNotBlank() && mixedText != mainText) choices["قراءة مختلطة"] = mixedText

            return OcrResult(
                primary = mainText,
                alternative = choices.values.firstOrNull { it != mainText },
                message = if (hasArabic) "تم تحليل العربية والإنجليزية في مواقعها بالصورة" else "تم تحليل النصوص ومقارنتها بعدة طرق",
                choices = choices.map { it.key to it.value }
            )
        } finally {
            if (cleaned !== scaled) cleaned.recycle()
            if (scaled !== original) scaled.recycle()
            original.recycle()
        }
    }

    /**
     * Segment phone screenshots into horizontal text bands. It finds bright
     * lettering against a dark UI (or dark lettering against a light UI).
     * Segmentation preserves lines that whole-page OCR sometimes drops.
     */
    private fun locateTextBands(bitmap: Bitmap): List<Rect> {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 100 || h < 50) return emptyList()
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var low = 0
        var count = 0
        for (y in 0 until h step maxOf(1, h / 32)) {
            for (x in 0 until w step maxOf(1, w / 32)) {
                val p = pixels[y * w + x]
                val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                if (lum < 125) low++
                count++
            }
        }
        val darkBackground = low > count / 2
        val left = maxOf(0, (w * .035f).toInt())
        val right = minOf(w, (w * .965f).toInt())
        val rowCount = IntArray(h)
        val foreground = BooleanArray(w * h)
        for (y in 0 until h) {
            var total = 0
            for (x in left until right) {
                val pixel = pixels[y * w + x]
                val lum = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                val matches = if (darkBackground) lum > 168 else lum < 94
                if (matches) {
                    foreground[y * w + x] = true
                    total++
                }
            }
            rowCount[y] = total
        }
        val minPixels = maxOf(10, w / 45)
        val active = BooleanArray(h) { rowCount[it] >= minPixels }
        // Join tiny holes caused by diacritics and dots, not adjacent lines.
        var i = 1
        while (i < h - 5) {
            if (active[i - 1] && !active[i]) {
                var next = i + 1
                while (next < minOf(h, i + 6) && !active[next]) next++
                if (next < minOf(h, i + 6)) {
                    for (y in i until next) active[y] = true
                    i = next
                }
            }
            i++
        }
        val bands = mutableListOf<Rect>()
        var y = 0
        while (y < h) {
            if (!active[y]) { y++; continue }
            val begin = y
            while (y < h && active[y]) y++
            val end = y
            if (end - begin < 13 || end - begin > h / 3) continue
            var minX = right
            var maxX = left
            for (yy in begin until end) {
                for (xx in left until right) {
                    if (foreground[yy * w + xx]) {
                        if (xx < minX) minX = xx
                        if (xx > maxX) maxX = xx
                    }
                }
            }
            if (maxX > minX && maxX - minX > w / 15) {
                bands.add(Rect(
                    maxOf(0, minX - 16),
                    maxOf(0, begin - 8),
                    minOf(w, maxX + 17),
                    minOf(h, end + 8)
                ))
            }
        }
        return bands.take(120)
    }

    private fun verticalOverlap(a: Rect, b: Rect): Float {
        val overlap = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        return overlap / minOf(a.height(), b.height()).coerceAtLeast(1).toFloat()
    }

    /**
     * Whole-page modes may skip individual lines (this happened to the user's
     * Arabic screenshot). Re-run ONLY missing or very weak bands as single
     * lines. Return one best Arabic result per visual band, no duplicates.
     */
    private fun fillMissingLines(
        dataPath: File,
        image: Bitmap,
        bands: List<Rect>,
        scriptLines: List<OcrLine>,
        languageLines: List<OcrLine>
    ): List<OcrLine> {
        if (bands.isEmpty()) return scriptLines.ifEmpty { languageLines }
        val all = (scriptLines + languageLines).filter { arabicCount(it.value) >= 2 }
        val output = mutableListOf<OcrLine>()
        for (band in bands) {
            val candidates = all.filter { verticalOverlap(it.bounds, band) > .42f }.toMutableList()
            val bestOnPage = candidates.maxByOrNull { arabicQuality(it) }
            if (bestOnPage == null || bestOnPage.confidence < 58f || arabicCount(bestOnPage.value) < 8) {
                try {
                    val clipped = Bitmap.createBitmap(image, band.left, band.top, band.width(), band.height())
                    try {
                        // The RAW_LINE segmentation is useful for Arabic UI captions
                        // rejected by automatic layout analysis.
                        val recognized = readTesseract(
                            dataPath, clipped, "Arabic",
                            TessBaseAPI.PageSegMode.PSM_RAW_LINE, "line"
                        )
                        candidates.addAll(recognized.lines.map { line ->
                            line.copy(bounds = Rect(
                                line.bounds.left + band.left,
                                line.bounds.top + band.top,
                                line.bounds.right + band.left,
                                line.bounds.bottom + band.top
                            ))
                        })
                        if (candidates.none { arabicCount(it.value) >= 3 }) {
                            val spare = readTesseract(
                                dataPath, clipped, "ara",
                                TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT, "line-fallback"
                            )
                            candidates.addAll(spare.lines.map { line ->
                                line.copy(bounds = Rect(
                                    line.bounds.left + band.left,
                                    line.bounds.top + band.top,
                                    line.bounds.right + band.left,
                                    line.bounds.bottom + band.top
                                ))
                            })
                        }
                    } finally {
                        clipped.recycle()
                    }
                } catch (_: Exception) {
                    // Keep successful whole-page results if a crop fails.
                }
            }
            val chosen = candidates.filter { arabicCount(it.value) >= 3 }
                .maxByOrNull { arabicQuality(it) }
            if (chosen != null) {
                output.add(chosen.copy(bounds = band))
            }
        }
        return if (output.isEmpty()) scriptLines.ifEmpty { languageLines } else output
    }

    private fun arabicQuality(line: OcrLine): Float {
        val text = line.value.trim()
        val arabicLetters = arabicCount(text)
        val nonArabicLatin = latinCount(text)
        val noisy = text.count { it == '|' || it == '\uFFFD' || it == '_' }
        return line.confidence * .62f +
            minOf(24, arabicLetters) * 1.0f +
            minOf(55, text.length) * .08f -
            nonArabicLatin * 1.9f - noisy * 3f
    }

    private fun isArabic(char: Char): Boolean =
        char in '\u0621'..'\u064A' || char in '\u0671'..'\u06D3'

    private fun arabicCount(text: String): Int = text.count { isArabic(it) }
    private fun latinCount(text: String): Int = text.count { it in 'A'..'Z' || it in 'a'..'z' }

    private fun chooseReading(one: TessReading, two: TessReading): TessReading {
        if (one.lines.isEmpty()) return two
        if (two.lines.isEmpty()) return one
        // Don't substitute a tiny high-confidence excerpt for a mostly complete page.
        if (two.lines.size >= one.lines.size && two.confidence > one.confidence + 6) return two
        if (two.lines.size > one.lines.size * 1.55 && two.confidence > one.confidence - 8) return two
        return one
    }

    private fun formatLines(lines: List<OcrLine>): String =
        lines.sortedWith(compareBy<OcrLine> { it.bounds.centerY() }.thenBy { it.bounds.left })
            .joinToString("\n") { it.value.trim() }
            .replace(Regex("\n{3,}"), "\n\n").trim()

    /**
     * Retain Arabic Tesseract lines and English ML Kit lines where they occupy
     * different visual regions. Overlapping results are alternatives for the
     * same line, not two separate lines that should both be copied.
     */
    private fun joinByPosition(
        english: List<OcrLine>,
        mixed: List<OcrLine>,
        arabicLines: List<OcrLine>
    ): String {
        val selected = mutableListOf<OcrLine>()
        val candidates = (mixed + arabicLines)
            .filter { arabicCount(it.value) >= 2 && it.confidence >= 35f }
            .sortedWith(compareByDescending<OcrLine> { it.confidence + arabicCount(it.value).coerceAtMost(20) }.thenBy { it.bounds.top })
        for (line in candidates) {
            val existing = selected.indexOfFirst { sameRegion(it.bounds, line.bounds) }
            if (existing < 0) selected.add(line)
            else {
                val previous = selected[existing]
                val prevScore = previous.confidence + min(arabicCount(previous.value), 12)
                val newScore = line.confidence + min(arabicCount(line.value), 12)
                if (newScore > prevScore + 3f) selected[existing] = line
            }
        }
        for (line in english) {
            val value = line.value.trim()
            if (value.length < 2) continue
            // ML Kit may hallucinate Latin glyphs over Arabic, so exclude
            // overlaps unless this is a clearly separate Latin-only region.
            if (selected.none { sameRegion(it.bounds, line.bounds) }) selected.add(line)
        }
        // If Tesseract hasn't confidently extracted Arabic, keep ML Kit's
        // English and mixed Tesseract reading as separate candidate views.
        if (selected.isEmpty()) selected.addAll(english.ifEmpty { mixed })
        return formatLines(selected)
    }

    private fun sameRegion(a: Rect, b: Rect): Boolean {
        if (!Rect.intersects(a, b)) return false
        val intersection = Rect()
        intersection.setIntersect(a, b)
        val w = min(a.width(), b.width()).coerceAtLeast(1)
        val h = min(a.height(), b.height()).coerceAtLeast(1)
        return intersection.width() > w * 0.26f &&
            intersection.height() > h * 0.44f
    }

    private fun readLatin(bitmap: Bitmap): List<OcrLine> {
        val reader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = Tasks.await(reader.process(InputImage.fromBitmap(bitmap, 0)))
            result.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    val value = line.text.trim()
                    val rect = line.boundingBox
                    if (value.isEmpty() || rect == null) null
                    else OcrLine(value, rect, 88f, "mlkit")
                }
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            reader.close()
        }
    }

    private fun readTesseract(
        dataPath: File,
        bitmap: Bitmap,
        languages: String,
        segmentation: Int,
        label: String
    ): TessReading {
        val api = TessBaseAPI()
        return try {
            if (!api.init(dataPath.absolutePath, languages, TessBaseAPI.OEM_LSTM_ONLY)) {
                TessReading(emptyList(), 0f)
            } else {
                api.setPageSegMode(segmentation)
                api.setVariable("preserve_interword_spaces", "1")
                api.setImage(bitmap)
                // The first read triggers recognition so confidence/iterator
                // values apply to the same page and segmentation mode.
                api.utF8Text
                val confidence = api.meanConfidence().toFloat()
                val list = ArrayList<OcrLine>()
                val iterator = api.resultIterator
                if (iterator != null) {
                    try {
                        iterator.begin()
                        do {
                            val value = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)?.trim()
                            if (!value.isNullOrBlank()) {
                                val rect = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                                val score = iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                                if (rect.width() > 1 && rect.height() > 1) {
                                    list.add(OcrLine(value, rect, score, label))
                                }
                            }
                        } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
                    } finally {
                        iterator.delete()
                    }
                }
                TessReading(list, confidence)
            }
        } catch (_: Exception) {
            TessReading(emptyList(), 0f)
        } finally {
            api.recycle()
        }
    }

    private fun setupTessdata(): File {
        val root = File(context.filesDir, "tesseract")
        val data = File(root, "tessdata")
        if (!data.exists() && !data.mkdirs()) error("Could not prepare OCR data directory")
        for (lang in listOf("ara", "eng", "Arabic")) {
            val filename = "$lang.traineddata"
            val output = File(data, filename)
            if (!output.exists() || output.length() < 100_000L) {
                val temporary = File(data, "$filename.tmp")
                try {
                    context.assets.open("tessdata/$filename").use { source ->
                        FileOutputStream(temporary).use { sink -> source.copyTo(sink) }
                    }
                    if (!temporary.renameTo(output)) {
                        temporary.copyTo(output, overwrite = true)
                        temporary.delete()
                    }
                } finally {
                    temporary.delete()
                }
            }
        }
        return root
    }

    private fun loadBitmap(uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val sample = max(1, max(info.size.width, info.size.height) / 3800)
                if (sample > 1) decoder.setTargetSampleSize(sample)
            }
        }
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
        opt.inSampleSize = max(1, max(opt.outWidth, opt.outHeight) / 3800)
        opt.inJustDecodeBounds = false
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
            ?: error("Couldn't decode the screenshot")
    }

    private fun enlargeForText(bitmap: Bitmap): Bitmap {
        val factor = min(2.2f, min(2600f / bitmap.width, 4500f / bitmap.height)).coerceAtLeast(1f)
        if (factor < 1.12f) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).toInt(), (bitmap.height * factor).toInt(), true)
    }

    /**
     * Screenshots are often dark-mode white-on-black text. Invert dark-majority
     * images for Tesseract, then increase contrast slightly. ML Kit sees the
     * original image so detail in colored fonts is not thrown away.
     */
    private fun improveContrast(source: Bitmap): Bitmap {
        var samples = 0
        var dark = 0
        val dx = max(1, source.width / 40)
        val dy = max(1, source.height / 40)
        for (y in 0 until source.height step dy) {
            for (x in 0 until source.width step dx) {
                val pixel = source.getPixel(x, y)
                val brightness = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                samples++
                if (brightness < 105) dark++
            }
        }
        val inverse = samples > 0 && dark > samples * 0.63
        val contrast = 1.26f
        val adjust = -22f
        val matrix = if (inverse) {
            ColorMatrix(floatArrayOf(
                -contrast * 0.299f, -contrast * 0.587f, -contrast * 0.114f, 0f, 255f - adjust,
                -contrast * 0.299f, -contrast * 0.587f, -contrast * 0.114f, 0f, 255f - adjust,
                -contrast * 0.299f, -contrast * 0.587f, -contrast * 0.114f, 0f, 255f - adjust,
                0f, 0f, 0f, 1f, 0f
            ))
        } else {
            ColorMatrix(floatArrayOf(
                contrast * 0.299f, contrast * 0.587f, contrast * 0.114f, 0f, adjust,
                contrast * 0.299f, contrast * 0.587f, contrast * 0.114f, 0f, adjust,
                contrast * 0.299f, contrast * 0.587f, contrast * 0.114f, 0f, adjust,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        })
        return output
    }
}
