package com.pkos.core.data.backup

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Thrown when the recovery key does not open the backup (GCM tag check on the wrapped data key fails). */
class WrongRecoveryKeyException : Exception("recovery key does not match this backup")

/** Thrown when any encrypted chunk or the archive structure fails authentication. */
class CorruptBackupException(msg: String) : Exception(msg)

/** Spec 8.3 key-derivation parameters (PBKDF2-HMAC-SHA256; Argon2id is not available in the platform JCA). */
data class KdfParams(val algorithm: String = ALG, val iterations: Int = DEFAULT_ITERATIONS, val salt: ByteArray) {
    fun toJson(): JSONObject = JSONObject().put("alg", algorithm).put("iterations", iterations).put("salt", b64(salt))

    companion object {
        const val ALG = "PBKDF2-HMAC-SHA256"
        /** OWASP 2023 recommendation for PBKDF2-HMAC-SHA256. */
        const val DEFAULT_ITERATIONS = 600_000
        fun fromJson(o: JSONObject) = KdfParams(o.getString("alg"), o.getInt("iterations"), unb64(o.getString("salt")))
    }
}

internal fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
internal fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

/**
 * Backup encryption (spec 8.3):
 * - a fresh random 256-bit data key (DEK) per backup encrypts the archive with AES-256-GCM in 1 MiB chunks;
 * - every chunk and the key wrap use their own random 96-bit nonce (never reused with a key);
 * - the DEK is wrapped with a key-encryption key (KEK) derived from the owner's recovery key;
 * - chunk AAD binds header hash, chunk index and the "last chunk" flag, so reordering or truncation is detected.
 */
object BackupCrypto {
    const val MAGIC = "PKOSBAK1"
    const val CHUNK = 1 shl 20
    private const val NONCE = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)

    /** Recovery key shown once to the owner: 26 base32 symbols (~128 bits) in groups, e.g. `ABCD-EFGH-…`. */
    fun newRecoveryKey(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no I, O, 0, 1
        val chars = (0 until 28).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        return chars.chunked(4).joinToString("-")
    }

    /** Case/space/dash-insensitive, so the owner can type it however they wrote it down. */
    fun normalize(recoveryKey: String) = recoveryKey.uppercase().filter { it.isLetterOrDigit() }

    fun deriveKek(recoveryKey: String, params: KdfParams): ByteArray {
        require(params.algorithm == KdfParams.ALG) { "unsupported KDF ${params.algorithm}" }
        val spec = PBEKeySpec(normalize(recoveryKey).toCharArray(), params.salt, params.iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }

    /** Short public id of a KEK (lets the app tell a wrong key from a different backup set without trying it). */
    fun keyId(kek: ByteArray): String = sha256Hex(("pkos-key-id".toByteArray() + kek)).take(16)

    fun wrap(kek: ByteArray, dek: ByteArray, keyId: String): Pair<ByteArray, ByteArray> {
        val nonce = randomBytes(NONCE)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD("pkos-dek|$keyId".toByteArray())
        return nonce to c.doFinal(dek)
    }

    fun unwrap(kek: ByteArray, nonce: ByteArray, wrapped: ByteArray, keyId: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD("pkos-dek|$keyId".toByteArray())
        return try { c.doFinal(wrapped) } catch (e: AEADBadTagException) { throw WrongRecoveryKeyException() }
    }

    /** Writes MAGIC, the plaintext header (needed to decrypt) and the encrypted chunks of [plain]. */
    fun encrypt(plain: File, out: File, header: JSONObject, dek: ByteArray) {
        val headerBytes = header.toString().toByteArray(Charsets.UTF_8)
        val headerHash = MessageDigest.getInstance("SHA-256").digest(headerBytes)
        DataOutputStream(out.outputStream().buffered(1 shl 16)).use { o ->
            o.write(MAGIC.toByteArray()); o.writeInt(headerBytes.size); o.write(headerBytes)
            plain.inputStream().buffered(1 shl 16).use { input ->
                val buf = ByteArray(CHUNK)
                var index = 0L
                var n = readFully(input, buf)
                while (true) {
                    val next = ByteArray(CHUNK)
                    val m = if (n == CHUNK) readFully(input, next) else 0
                    val last = m <= 0
                    val nonce = randomBytes(NONCE)
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                    c.updateAAD(aad(headerHash, index, last))
                    val ct = c.doFinal(buf, 0, maxOf(n, 0))
                    o.writeInt(maxOf(n, 0)); o.write(nonce); o.write(ct)
                    if (last) break
                    System.arraycopy(next, 0, buf, 0, m); n = m; index++
                }
            }
        }
    }

    /** Reads only the plaintext header of an archive. */
    fun readHeader(file: File): JSONObject = DataInputStream(file.inputStream().buffered()).use { readHeader(it).first }

    private fun readHeader(input: DataInputStream): Pair<JSONObject, ByteArray> {
        val magic = ByteArray(MAGIC.length)
        try { input.readFully(magic) } catch (e: EOFException) { throw CorruptBackupException("too short") }
        if (String(magic) != MAGIC) throw CorruptBackupException("not a PKOS backup")
        val len = input.readInt()
        if (len !in 1..(1 shl 20)) throw CorruptBackupException("bad header length")
        val bytes = ByteArray(len).also(input::readFully)
        return try { JSONObject(String(bytes, Charsets.UTF_8)) to bytes } catch (e: Exception) { throw CorruptBackupException("bad header") }
    }

    /** Authenticates every chunk while decrypting; any tampering, reordering or truncation throws. */
    fun decrypt(archive: File, out: File, dek: ByteArray) {
        DataInputStream(archive.inputStream().buffered(1 shl 16)).use { input ->
            val (_, headerBytes) = readHeader(input)
            val headerHash = MessageDigest.getInstance("SHA-256").digest(headerBytes)
            out.outputStream().buffered(1 shl 16).use { o ->
                var index = 0L
                while (true) {
                    val len = try { input.readInt() } catch (e: EOFException) { throw CorruptBackupException("truncated archive") }
                    if (len !in 0..CHUNK) throw CorruptBackupException("bad chunk length")
                    val nonce = ByteArray(NONCE); val ct = ByteArray(len + TAG_BITS / 8)
                    try { input.readFully(nonce); input.readFully(ct) } catch (e: EOFException) { throw CorruptBackupException("truncated chunk") }
                    val last = input.available() == 0 && peekEnd(input)
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                    c.updateAAD(aad(headerHash, index, last))
                    val plain = try { c.doFinal(ct) } catch (e: AEADBadTagException) { throw CorruptBackupException("chunk $index failed authentication") }
                    o.write(plain)
                    if (last) break
                    index++
                }
            }
        }
    }

    private fun peekEnd(input: DataInputStream): Boolean {
        input.mark(1)
        val b = input.read()
        if (b == -1) return true
        input.reset(); return false
    }

    private fun aad(headerHash: ByteArray, index: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(headerHash.size + 9).put(headerHash).putLong(index).put(if (last) 1 else 0).array()

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) { val r = input.read(buf, off, buf.size - off); if (r < 0) break; off += r }
        return off
    }
}

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun File.digestHex(algorithm: String): String {
    val md = MessageDigest.getInstance(algorithm)
    inputStream().buffered(1 shl 16).use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
    return md.digest().joinToString("") { "%02x".format(it) }
}

internal fun OutputStream.writeString(s: String) = write(s.toByteArray(Charsets.UTF_8))
