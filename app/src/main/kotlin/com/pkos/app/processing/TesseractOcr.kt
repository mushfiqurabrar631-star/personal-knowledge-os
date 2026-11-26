package com.pkos.app.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.googlecode.tesseract.android.TessBaseAPI
import com.pkos.core.data.processing.OcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Offline Bengali OCR with Tesseract (tess-two, legacy `ben` model bundled in assets). Owner decision 2026-11-27:
 * images are never sent to a cloud service for OCR. One engine at a time (Tesseract is not thread-safe).
 */
class TesseractBengaliOcr(private val context: Context) {
    private val lock = Mutex()

    /** Copies the model out of the APK once (verified by SHA-256) and returns the folder Tesseract expects. */
    private fun dataDir(): File {
        val root = File(context.noBackupFilesDir, "tesseract")
        val target = File(root, "tessdata/ben.traineddata")
        if (!target.exists() || target.length() != MODEL_SIZE) {
            target.parentFile!!.mkdirs()
            val tmp = File(target.path + ".tmp")
            context.assets.open("tessdata/ben.traineddata").use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            val sha = MessageDigest.getInstance("SHA-256").let { md -> tmp.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }; md.digest() }
                .joinToString("") { "%02x".format(it) }
            check(sha == MODEL_SHA256) { "Bengali OCR model damaged" }
            check(tmp.renameTo(target))
        }
        return root
    }

    /** Returns Bengali text only when Tesseract is reasonably sure (otherwise null, never noise). */
    suspend fun read(bitmap: Bitmap): String? = withContext(Dispatchers.Default) {
        lock.withLock {
            val api = TessBaseAPI()
            try {
                if (!api.init(dataDir().path, "ben")) return@withLock null
                api.setImage(bitmap)
                val text = api.utF8Text.orEmpty().trim()
                val confidence = api.meanConfidence()
                text.takeIf { confidence >= MIN_CONFIDENCE && bengaliShare(it) >= 0.5 && it.count(::isBengali) >= 8 }
            } finally {
                runCatching { api.end() }
            }
        }
    }

    companion object {
        const val MODEL_SIZE = 15_552_533L
        const val MODEL_SHA256 = "157826baf48063547b7793e1bde0c9f82d4843488fb5c7e02155f662a708e860"
        const val MIN_CONFIDENCE = 45
        fun isBengali(c: Char) = c in 'ঀ'..'৿'
        fun bengaliShare(s: String): Double { val letters = s.count { it.isLetter() }; return if (letters == 0) 0.0 else s.count(::isBengali).toDouble() / letters }
    }
}

/**
 * Spec 3 OCR: ML Kit (Latin) + Tesseract (Bengali), both on the phone. Each engine may fail without failing the other.
 * Scanned PDFs: the first [MAX_PDF_PAGES] pages are rendered with the platform PdfRenderer and read the same way.
 */
class CombinedOcrEngine(
    private val context: Context,
    private val latin: OcrEngine = MlKitOcrEngine(context),
    private val bengali: TesseractBengaliOcr = TesseractBengaliOcr(context),
) : OcrEngine {
    override suspend fun recognize(file: File): String {
        val l = runCatching { latin.recognize(file) }
        val b = runCatching { decode(file)?.let { bm -> try { bengali.read(bm) } finally { bm.recycle() } } }.getOrNull()
        if (l.isFailure && b == null) throw l.exceptionOrNull()!!
        return merge(l.getOrNull(), b)
    }

    override suspend fun recognizePdf(file: File): String? = withContext(Dispatchers.IO) {
        runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { pdf ->
                    (0 until minOf(pdf.pageCount, MAX_PDF_PAGES)).mapNotNull { i ->
                        val page = pdf.openPage(i)
                        val scale = PDF_WIDTH.toFloat() / page.width
                        val bm = Bitmap.createBitmap(PDF_WIDTH, (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                        page.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); page.close()
                        val tmp = File.createTempFile("pkos-ocr", ".png", context.cacheDir)
                        try {
                            tmp.outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            val l = runCatching { latin.recognize(tmp) }.getOrNull()
                            merge(l, runCatching { bengali.read(bm) }.getOrNull()).ifBlank { null }
                        } finally { tmp.delete(); bm.recycle() }
                    }.joinToString("\n\n").ifBlank { null }
                }
            }
        }.getOrNull()
    }

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2 // bounded memory for huge photos
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        const val MAX_PDF_PAGES = 10
        const val PDF_WIDTH = 1600
        const val MAX_SIDE = 2400
        fun merge(latin: String?, bengali: String?): String =
            listOfNotNull(latin?.trim()?.takeIf { it.isNotEmpty() }, bengali?.trim()?.takeIf { it.isNotEmpty() && it != latin?.trim() }).joinToString("\n")
    }
}
