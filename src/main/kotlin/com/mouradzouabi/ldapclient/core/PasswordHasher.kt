package com.mouradzouabi.ldapclient.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

enum class PasswordScheme(val label: String, val prefix: String?, val algorithm: String?, val salted: Boolean) {
    SSHA512("SSHA-512 (salted SHA-512)", "SSHA512", "SHA-512", true),
    SSHA256("SSHA-256 (salted SHA-256)", "SSHA256", "SHA-256", true),
    SSHA("SSHA (salted SHA-1)", "SSHA", "SHA-1", true),
    SHA512("SHA-512", "SHA512", "SHA-512", false),
    SHA256("SHA-256", "SHA256", "SHA-256", false),
    SHA("SHA-1", "SHA", "SHA-1", false),
    SMD5("SMD5 (salted MD5)", "SMD5", "MD5", true),
    MD5("MD5", "MD5", "MD5", false),
    PLAIN("Plain text (server may hash it)", null, null, false),
    AD_UNICODE_PWD("Active Directory unicodePwd", null, null, false);

    override fun toString() = label

    companion object {
        fun ofPrefix(prefix: String): PasswordScheme? = entries.firstOrNull { it.prefix.equals(prefix, ignoreCase = true) }
    }
}

object PasswordHasher {
    private val random = SecureRandom()

    fun encode(password: String, scheme: PasswordScheme): ByteArray = when (scheme) {
        PasswordScheme.PLAIN -> password.toByteArray(Charsets.UTF_8)
        PasswordScheme.AD_UNICODE_PWD -> "\"$password\"".toByteArray(Charsets.UTF_16LE)
        else -> {
            val salt = if (scheme.salted) ByteArray(8).also(random::nextBytes) else ByteArray(0)
            val digest = digest(scheme.algorithm!!, password.toByteArray(Charsets.UTF_8), salt)
            "{${scheme.prefix}}${Base64.getEncoder().encodeToString(digest + salt)}".toByteArray(Charsets.UTF_8)
        }
    }

    /**
     * Checks [candidate] against a stored value. Returns null when the scheme is not supported
     * for verification (e.g. CRYPT, server specific schemes).
     */
    fun verify(stored: ByteArray, candidate: String): Boolean? {
        val text = String(stored, Charsets.UTF_8)
        val match = Regex("^\\{([^}]+)}(.*)$", RegexOption.DOT_MATCHES_ALL).find(text)
            ?: return text == candidate
        val scheme = PasswordScheme.ofPrefix(match.groupValues[1]) ?: return null
        val algorithm = scheme.algorithm ?: return null
        val decoded = try {
            Base64.getDecoder().decode(match.groupValues[2].trim())
        } catch (_: IllegalArgumentException) {
            return false
        }
        val length = MessageDigest.getInstance(algorithm).digestLength
        if (decoded.size < length) return false
        val hash = decoded.copyOfRange(0, length)
        val salt = decoded.copyOfRange(length, decoded.size)
        return MessageDigest.isEqual(hash, digest(algorithm, candidate.toByteArray(Charsets.UTF_8), salt))
    }

    private fun digest(algorithm: String, password: ByteArray, salt: ByteArray): ByteArray =
        MessageDigest.getInstance(algorithm).run {
            update(password)
            update(salt)
            digest()
        }
}
