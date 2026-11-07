package com.mouradzouabi.ldapclient.core

import com.unboundid.ldap.listener.InMemoryDirectoryServer
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig
import com.unboundid.ldap.listener.InMemoryListenerConfig
import com.unboundid.ldap.sdk.Entry
import com.unboundid.ldap.sdk.Filter
import com.unboundid.ldap.sdk.LDAPConnectionPool
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.ResultCode
import com.unboundid.ldap.sdk.SearchScope
import com.mouradzouabi.ldapclient.model.LdapConnectionConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LdapSessionTest {
    private lateinit var server: InMemoryDirectoryServer
    private lateinit var session: LdapSession

    private val base = "dc=example,dc=com"
    private val people = "ou=people,$base"

    @Before
    fun setUp() {
        val cfg = InMemoryDirectoryServerConfig(base).apply {
            setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", 0))
        }
        server = InMemoryDirectoryServer(cfg)
        server.add("dn: $base", "objectClass: top", "objectClass: domain", "dc: example")
        server.add("dn: $people", "objectClass: top", "objectClass: organizationalUnit", "ou: people")
        for (i in 0 until 95) {
            server.add("dn: uid=user$i,$people", "objectClass: top", "objectClass: inetOrgPerson",
                "uid: user$i", "cn: User $i", "sn: User")
        }
        server.add("dn: ou=deep,$base", "objectClass: organizationalUnit", "ou: deep")
        server.add("dn: ou=a,ou=deep,$base", "objectClass: organizationalUnit", "ou: a")
        server.add("dn: ou=b,ou=a,ou=deep,$base", "objectClass: organizationalUnit", "ou: b")
        server.add("dn: cn=leaf,ou=b,ou=a,ou=deep,$base", "objectClass: device", "cn: leaf")
        server.startListening()

        val config = LdapConnectionConfig().apply {
            name = "test"
            port = server.listenPort
            baseDn = base
            pageSize = 10
        }
        val first = server.connection
        val rootDse = first.rootDSE
        session = LdapSession(config, LDAPConnectionPool(first, 1, 5), rootDse)
    }

    @After
    fun tearDown() {
        session.close()
        server.shutDown(true)
    }

    private fun peopleCursor() =
        session.cursor(people, SearchScope.ONE, Filter.createPresenceFilter("objectClass"), listOf("uid"))

    @Test
    fun `cursor loads children page by page`() = runBlocking {
        val cursor = peopleCursor()
        val seen = mutableSetOf<String>()
        var pages = 0
        while (!cursor.exhausted) {
            val page = cursor.nextPage()
            assertTrue("page too large: ${page.size}", page.size <= 10)
            page.forEach { assertTrue("duplicate ${it.dn}", seen.add(it.dn)) }
            pages++
        }
        assertEquals(95, seen.size)
        assertEquals(10, pages)
        assertFalse(cursor.truncated)
        cursor.close()
    }

    @Test
    fun `cursor resumes without duplicates after its connection was released`() = runBlocking {
        val cursor = peopleCursor()
        val seen = mutableListOf<String>()
        seen += cursor.nextPage().map { it.dn }
        seen += cursor.nextPage().map { it.dn }
        cursor.releaseIfIdle()
        assertFalse(cursor.holdsConnection)
        assertFalse(cursor.exhausted)
        while (!cursor.exhausted) seen += cursor.nextPage().map { it.dn }
        assertEquals(95, seen.size)
        assertEquals(95, seen.toSet().size)
        cursor.close()
    }

    @Test
    fun `idle cursors are released by the session`() = runBlocking {
        val cursor = peopleCursor()
        cursor.nextPage()
        assertTrue(cursor.holdsConnection)
        Thread.sleep(5)
        session.releaseIdleCursors(0)
        assertFalse(cursor.holdsConnection)
        cursor.close()
    }

    @Test
    fun `discarded cursor stops loading`() = runBlocking {
        val cursor = peopleCursor()
        cursor.nextPage()
        cursor.releaseIfIdle(force = true)
        assertTrue(cursor.exhausted)
        assertTrue(cursor.nextPage().isEmpty())
    }

    @Test
    fun `search streams entries and honours the client limit`() = runBlocking {
        val spec = SearchSpec(base, SearchScope.SUB, Filter.create("(objectClass=inetOrgPerson)"), listOf("uid"))
        val all = mutableListOf<String>()
        val outcome = session.search(spec, 0) { all += it.dn }
        assertEquals(95, outcome.count)
        assertFalse(outcome.truncated)
        assertEquals(95, all.toSet().size)

        val limited = mutableListOf<String>()
        val partial = session.search(spec, 25) { limited += it.dn }
        assertEquals(25, partial.count)
        assertTrue(partial.truncated)
        assertEquals(25, limited.size)
    }

    @Test
    fun `entry diff is accepted by the server`() = runBlocking {
        val dn = "uid=user1,$people"
        val original = session.getEntry(dn, listOf("*"))!!
        val before = original.attributes.associate { it.name to it.rawValues.map { v -> v.value } }
        val after = before.toMutableMap().apply {
            put("cn", listOf("User 1".toByteArray(), "Alias".toByteArray()))
            put("mail", listOf("u1@example.com".toByteArray()))
            put("sn", listOf("Renamed".toByteArray()))
        }
        val mods = EntryDiff.compute(before, after)
        session.modify(dn, mods)
        val updated = session.getEntry(dn, listOf("*"))!!
        assertEquals(setOf("User 1", "Alias"), updated.getAttributeValues("cn").toSet())
        assertEquals("u1@example.com", updated.getAttributeValue("mail"))
        assertEquals("Renamed", updated.getAttributeValue("sn"))
        assertTrue(EntryDiff.compute(after, after).isEmpty())
    }

    @Test
    fun `add rename and delete`() = runBlocking {
        session.add(Entry("dn: ou=tmp,$base", "objectClass: organizationalUnit", "ou: tmp"))
        session.rename("ou=tmp,$base", "ou=tmp2")
        assertNull(session.getEntry("ou=tmp,$base", listOf("*")))
        session.delete("ou=tmp2,$base", recursive = false)
        assertNull(session.getEntry("ou=tmp2,$base", listOf("*")))
    }

    @Test
    fun `non recursive delete of a parent fails and recursive delete removes the subtree`() = runBlocking {
        val dn = "ou=deep,$base"
        val error = runCatching { session.delete(dn, recursive = false) }.exceptionOrNull()
        assertEquals(ResultCode.NOT_ALLOWED_ON_NONLEAF, (error as LDAPException).resultCode)
        session.delete(dn, recursive = true)
        assertNull(session.getEntry(dn, listOf("*")))
        assertNull(session.getEntry("cn=leaf,ou=b,ou=a,$dn", listOf("*")))
    }

    @Test
    fun `read only connections reject writes`() = runBlocking {
        session.config.readOnly = true
        val error = runCatching { session.delete("uid=user1,$people", recursive = false) }.exceptionOrNull()
        assertTrue(error != null)
        assertTrue(session.getEntry("uid=user1,$people", listOf("*")) != null)
    }
}
