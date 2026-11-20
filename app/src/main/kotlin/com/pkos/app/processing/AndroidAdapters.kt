package com.pkos.app.processing

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pkos.core.data.processing.HttpPost
import com.pkos.core.data.processing.HttpReply
import com.pkos.core.data.processing.OcrEngine
import com.pkos.core.data.processing.TextExtractor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** PDF text via PdfBox-Android (on device, no network). Scanned PDFs without a text layer return "". */
class PdfTextExtractor(private val context: Context) : TextExtractor {
    @Volatile private var ready = false
    override suspend fun extract(file: File, mimeType: String?): String = withContext(Dispatchers.IO) {
        if (!ready) { PDFBoxResourceLoader.init(context.applicationContext); ready = true }
        PDDocument.load(file).use { doc ->
            PDFTextStripper().apply { endPage = minOf(doc.numberOfPages, 50) }.getText(doc)
        }
    }
}

/**
 * On-device OCR with ML Kit (bundled Latin model; works offline, nothing uploaded).
 * Limitation: the Latin model does not read Bengali script; Bengali screenshots get little or no text.
 */
class MlKitOcrEngine(private val context: Context) : OcrEngine {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    override suspend fun recognize(file: File): String {
        val image = InputImage.fromFilePath(context, Uri.fromFile(file))
        return suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it.text) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }
}

/** HttpURLConnection transport (no extra HTTP library). Bodies are never logged. */
object UrlConnectionHttp : HttpPost {
    override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpReply = withContext(Dispatchers.IO) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 45_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            HttpReply(status, text, c.getHeaderField("Retry-After")?.toLongOrNull())
        } finally {
            c.disconnect()
        }
    }
}

/** Where the owner's API key lives. Never in code, logs, backups or Room (spec: no secrets in code). */
interface SecretStore {
    fun get(name: String): String?
    fun put(name: String, value: String)
    fun remove(name: String)
}

/** AES-GCM with a non-exportable Android Keystore key; only ciphertext is stored (excluded from backups). */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.applicationContext.getSharedPreferences("pkos_secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    override fun get(name: String): String? {
        val blob = prefs.getString(name, null) ?: return null
        return runCatching {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12)) }
            String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun put(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit { putString(name, Base64.encodeToString(out, Base64.NO_WRAP)) }
    }

    override fun remove(name: String) { prefs.edit { remove(name) } }

    private companion object {
        const val ALIAS = "pkos_secrets_v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

class InMemorySecretStore : SecretStore {
    private val map = mutableMapOf<String, String>()
    override fun get(name: String) = map[name]
    override fun put(name: String, value: String) { map[name] = value }
    override fun remove(name: String) { map.remove(name) }
}

const val GEMINI_KEY = "gemini_api_key"
