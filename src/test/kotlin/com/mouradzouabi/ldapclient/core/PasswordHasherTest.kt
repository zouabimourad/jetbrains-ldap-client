package com.mouradzouabi.ldapclient.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordHasherTest {
    @Test
    fun `hashed schemes verify`() {
        for (scheme in PasswordScheme.entries.filter { it.algorithm != null }) {
            val encoded = PasswordHasher.encode("s3cret", scheme)
            assertTrue(String(encoded).startsWith("{${scheme.prefix}}"))
            assertEquals(scheme.name, true, PasswordHasher.verify(encoded, "s3cret"))
            assertEquals(scheme.name, false, PasswordHasher.verify(encoded, "wrong"))
        }
    }

    @Test
    fun `salted hashes differ`() {
        val a = PasswordHasher.encode("x", PasswordScheme.SSHA)
        val b = PasswordHasher.encode("x", PasswordScheme.SSHA)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `known openldap ssha value verifies`() {
        // slappasswd -h {SHA} -s secret
        assertEquals(true, PasswordHasher.verify("{SHA}5en6G6MezRroT3XKqkdPOmY/BfQ=".toByteArray(), "secret"))
    }

    @Test
    fun `unicodePwd is quoted utf16le`() {
        val encoded = PasswordHasher.encode("ab", PasswordScheme.AD_UNICODE_PWD)
        assertEquals("\"ab\"", String(encoded, Charsets.UTF_16LE))
    }

    @Test
    fun `unknown scheme cannot be verified`() {
        assertNull(PasswordHasher.verify("{CRYPT}abc".toByteArray(), "x"))
    }
}
