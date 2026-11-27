package com.pkos.core.data.backup

import org.json.JSONObject
import java.io.File

/**
 * One attachment = one encrypted blob (spec 8.1 "incremental attachment upload"): same AES-256-GCM chunked format as
 * the archive, with its own random data key wrapped by the recovery-key-derived key. The name carries key id + SHA-256,
 * so an unchanged file is uploaded only once and never mixed with a blob made under another recovery key.
 */
object BackupBlobs {
    const val EXT = ".pkosblob"
    const val PROP_SHA = "pkos_blob_sha"

    fun encrypt(plain: File, out: File, kek: ByteArray, keyId: String, sha256: String) {
        val dek = BackupCrypto.randomBytes(32)
        try {
            val (nonce, wrapped) = BackupCrypto.wrap(kek, dek, keyId)
            val header = JSONObject().put("format", 2).put("kind", "blob").put("sha256", sha256).put("size", plain.length())
                .put("cipher", "AES-256-GCM").put("chunk_size", BackupCrypto.CHUNK).put("key_id", keyId)
                .put("wrapped_key", JSONObject().put("nonce", b64(nonce)).put("ciphertext", b64(wrapped)))
            BackupCrypto.encrypt(plain, out, header, dek)
        } finally { dek.fill(0) }
    }

    /** Decrypts and checks that the content really is the file the manifest names. */
    fun decrypt(blob: File, out: File, kek: ByteArray, keyId: String, expectedSha: String) {
        val h = BackupCrypto.readHeader(blob)
        if (h.optString("kind") != "blob" || h.getString("key_id") != keyId) throw WrongRecoveryKeyException()
        if (h.getString("sha256") != expectedSha) throw CorruptBackupException("blob is for another file")
        val w = h.getJSONObject("wrapped_key")
        val dek = BackupCrypto.unwrap(kek, unb64(w.getString("nonce")), unb64(w.getString("ciphertext")), keyId)
        try { BackupCrypto.decrypt(blob, out, dek) } finally { dek.fill(0) }
        if (out.digestHex("SHA-256") != expectedSha) { out.delete(); throw CorruptBackupException("blob checksum mismatch") }
    }
}
