package com.example.audiostreamer.update

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Verifies file integrity using cryptographic SHA-256 hashes.
 */
object ChecksumVerifier {

    /**
     * Calculates the SHA-256 hex string for the given file.
     */
    fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val hashBytes = digest.digest()
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Parses the expected 64-character SHA-256 hex hash from raw text
     * (which may be formatted like "hash  filename" or just "hash").
     */
    fun parseExpectedHash(rawText: String): String? {
        val match = Regex("""\b([a-fA-F0-9]{64})\b""").find(rawText.trim())
        return match?.groupValues?.get(1)?.lowercase()
    }

    /**
     * Verifies that the file's SHA-256 matches the expected hash.
     */
    fun verify(file: File, expectedRawHashOrText: String): Boolean {
        val expected = parseExpectedHash(expectedRawHashOrText) ?: return false
        val actual = calculateSha256(file)
        return expected.equals(actual, ignoreCase = true)
    }

    /**
     * Verifies a checksum required for installation. Missing, malformed, or mismatched
     * checksum data is an error rather than a reason to continue without verification.
     */
    fun verifyRequired(file: File, expectedRawHashOrText: String?) {
        val expected = expectedRawHashOrText?.let(::parseExpectedHash)
            ?: throw SecurityException("Missing or invalid SHA-256 checksum")
        val actual = calculateSha256(file)
        if (!expected.equals(actual, ignoreCase = true)) {
            throw SecurityException("SHA-256 checksum mismatch. Expected: $expected; actual: $actual")
        }
    }
}
