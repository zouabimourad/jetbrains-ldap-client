package com.mouradzouabi.ldapclient.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LdapDnAndKindsTest {
    @Test
    fun `dn helpers`() {
        assertEquals("ou=people,dc=example,dc=com", LdapDn.parent("uid=a,ou=people,dc=example,dc=com"))
        assertEquals("uid=a", LdapDn.rdn("uid=a,ou=people,dc=example,dc=com"))
        assertTrue(LdapDn.same("UID=a, OU=People,dc=example,dc=com", "uid=a,ou=people,dc=example,dc=com"))
    }

    @Test
    fun `attribute classification`() {
        assertEquals(AttributeKind.PASSWORD, AttributeKinds.classify("userPassword", LdapSchema(null), listOf("{SSHA}x".toByteArray())))
        assertEquals(AttributeKind.TEXT, AttributeKinds.classify("cn", LdapSchema(null), listOf("x".toByteArray())))
        assertEquals(AttributeKind.BINARY, AttributeKinds.classify("foo", LdapSchema(null), listOf(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0))))
    }

    @Test
    fun `password values are masked`() {
        val text = AttributeKinds.display(AttributeKind.PASSWORD, "{SSHA}abcdef".toByteArray())
        assertTrue(text.startsWith("{SSHA}"))
        assertTrue(!text.contains("abcdef"))
    }
}
