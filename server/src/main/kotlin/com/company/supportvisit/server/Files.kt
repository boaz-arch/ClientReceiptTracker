package com.company.supportvisit.server

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Stores visit files (PDF reports, photos) on disk: <data>/files/<tenant>/<visitId>/<name>. */
class FileStore(dataDir: File, private val maxBytes: Long) {

    private val root = File(dataDir, "files").apply { mkdirs() }

    companion object {
        private val SAFE_NAME = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]{0,120}$""")
        private val SAFE_ID = Regex("""^[A-Za-z0-9-]{1,64}$""")

        fun isSafeName(name: String) = SAFE_NAME.matches(name) && !name.contains("..")
        fun isSafeId(id: String) = SAFE_ID.matches(id)

        fun contentTypeFor(name: String): String = when (name.substringAfterLast('.').lowercase()) {
            "logo" -> "image/*"
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }
    }

    fun file(tenantId: String, visitId: String, name: String): File {
        require(isSafeId(tenantId) && isSafeId(visitId) && isSafeName(name)) { "Invalid file path" }
        return File(File(File(root, tenantId), visitId), name)
    }

    /** Company assets live outside the visit files, so no visit id can collide with them. */
    private val companyRoot = File(dataDir, "company").apply { mkdirs() }

    /** The company logo (PNG or JPEG, max 2 MB). */
    fun companyLogo(tenantId: String): File {
        require(isSafeId(tenantId)) { "Invalid path" }
        return File(File(companyRoot, tenantId), "logo")
    }

    fun saveCompanyLogo(tenantId: String, input: InputStream) {
        val target = companyLogo(tenantId)
        target.parentFile.mkdirs()
        val bytes = input.readNBytes(2 * 1024 * 1024 + 1)
        require(bytes.size <= 2 * 1024 * 1024) { "Logo too large" }
        val png = bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
        val jpeg = bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        require(png || jpeg) { "Logo must be PNG or JPEG" }
        target.writeBytes(bytes)
    }

    /** Deletes every file of a visit (PDF and photos). */
    fun deleteVisit(tenantId: String, visitId: String) {
        require(isSafeId(tenantId) && isSafeId(visitId)) { "Invalid path" }
        File(File(root, tenantId), visitId).deleteRecursively()
    }

    /** Writes the upload to a temp file first, so a failed upload never leaves a half file. */
    fun save(tenantId: String, visitId: String, name: String, input: InputStream): Long {
        val target = file(tenantId, visitId, name)
        target.parentFile.mkdirs()
        val temp = File(target.parentFile, ".$name.uploading")
        var total = 0L
        try {
            temp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) throw IllegalArgumentException("File too large")
                    out.write(buffer, 0, read)
                }
            }
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IllegalStateException("Could not store file")
            }
        } finally {
            temp.delete()
        }
        return total
    }
}

/** Short-lived links so a phone can open a PDF in a browser or viewer without a token. */
class LinkSigner(secret: String) {

    private val key = SecretKeySpec(secret.toByteArray(), "HmacSHA256")

    fun sign(path: String, expiresAtSec: Long): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        val raw = mac.doFinal("$path:$expiresAtSec".toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    fun verify(path: String, expiresAtSec: Long, signature: String, nowSec: Long = System.currentTimeMillis() / 1000): Boolean {
        if (expiresAtSec < nowSec) return false
        return MessageDigest.isEqual(sign(path, expiresAtSec).toByteArray(), signature.toByteArray())
    }
}
