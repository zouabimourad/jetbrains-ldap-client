package com.mouradzouabi.ldapclient.core

import com.unboundid.ldap.listener.InMemoryDirectoryServer
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSearchEntry
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig
import com.unboundid.ldap.listener.InMemoryListenerConfig
import com.unboundid.ldap.sdk.Filter
import com.unboundid.ldap.sdk.LDAPConnectionPool
import com.unboundid.ldif.LDIFReader
import com.mouradzouabi.ldapclient.model.LdapConnectionConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.StringReader

class LdifExportTest {
    private lateinit var server: InMemoryDirectoryServer
    private lateinit var session: LdapSession
    private val base = "dc=example,dc=com"

    /** Simulates servers that return operational attributes even when only "*" is requested. */
    @Volatile
    private var leakOperational = false

    @Before
    fun setUp() {
        server = InMemoryDirectoryServer(InMemoryDirectoryServerConfig(base).apply {
            setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", 0))
            addInMemoryOperationInterceptor(object : InMemoryOperationInterceptor() {
                override fun processSearchEntry(entry: InMemoryInterceptedSearchEntry) {
                    if (!leakOperational) return
                    val copy = entry.searchEntry.duplicate()
                    copy.setAttribute("modifyTimestamp", "20260101000000Z")
                    copy.setAttribute("memberOf", "cn=staff,$base")
                    entry.setSearchEntry(copy)
                }
            })
        })
        server.add("dn: $base", "objectClass: domain", "dc: example")
        server.add("dn: ou=people,$base", "objectClass: organizationalUnit", "ou: people")
        server.add("dn: ou=staff,ou=people,$base", "objectClass: organizationalUnit", "ou: staff")
        for (i in 1..5) {
            server.add("dn: uid=u$i,ou=staff,ou=people,$base", "objectClass: inetOrgPerson", "uid: u$i", "cn: U$i", "sn: U$i")
        }
        server.add("dn: uid=top,ou=people,$base", "objectClass: inetOrgPerson", "uid: top", "cn: Top", "sn: Top", "userPassword: secret")
        server.startListening()
        val config = LdapConnectionConfig().apply { port = server.listenPort }
        val conn = server.connection
        session = LdapSession(config, LDAPConnectionPool(conn, 1, 3), conn.rootDSE)
    }

    @After
    fun tearDown() {
        session.close()
        server.shutDown(true)
    }

    private fun export(dn: String, options: LdifExportOptions): Pair<LdifExportSummary, String> = runBlocking {
        val out = ByteArrayOutputStream()
        val summary = LdifExport.export(session, dn, options, out)
        summary to out.toString(Charsets.UTF_8)
    }

    private fun dns(ldif: String): List<String> {
        val reader = LDIFReader(java.io.BufferedReader(StringReader(ldif)))
        return generateSequence { reader.readEntry() }.map { it.dn }.toList()
    }

    @Test
    fun `subtree export writes parents before children`() {
        val (summary, ldif) = export("ou=people,$base", LdifExportOptions(scope = ExportScope.SUBTREE))
        assertEquals(8, summary.entries)
        val dns = dns(ldif)
        assertEquals(8, dns.size)
        val seen = HashSet<String>()
        for (dn in dns) {
            val parent = LdapDn.parse(dn)!!.parent!!.toNormalizedString()
            assertTrue("parent of $dn written first", dn == "ou=people,$base" || parent in seen)
            seen += LdapDn.parse(dn)!!.toNormalizedString()
        }
        assertTrue(ldif.contains("userPassword"))
    }

    @Test
    fun `scopes and filters`() {
        assertEquals(1, export("ou=people,$base", LdifExportOptions(scope = ExportScope.ENTRY)).first.entries)
        assertEquals(2, export("ou=people,$base", LdifExportOptions(scope = ExportScope.CHILDREN)).first.entries)
        val filtered = export(base, LdifExportOptions(filter = Filter.create("(objectClass=inetOrgPerson)")))
        assertEquals(6, filtered.first.entries)
    }

    @Test
    fun `operational attributes and attribute selection`() {
        val plain = export("uid=top,ou=people,$base", LdifExportOptions(scope = ExportScope.ENTRY)).second
        assertTrue(!plain.contains("entryUUID", ignoreCase = true))
        val withOp = export("uid=top,ou=people,$base", LdifExportOptions(scope = ExportScope.ENTRY, includeOperational = true)).second
        assertTrue(withOp.contains("entryUUID", ignoreCase = true))
        val selected = export("uid=top,ou=people,$base", LdifExportOptions(scope = ExportScope.ENTRY, attributes = listOf("cn"))).second
        assertTrue(selected.contains("cn: Top") && !selected.contains("sn:"))
    }

    @Test
    fun `operational attributes returned for star are left out unless included or listed`() {
        leakOperational = true
        val dn = "uid=top,ou=people,$base"
        val plain = export(dn, LdifExportOptions(scope = ExportScope.ENTRY)).second
        assertTrue(plain.contains("cn: Top") && plain.contains("userPassword"))
        assertTrue(!plain.contains("modifyTimestamp", ignoreCase = true) && !plain.contains("memberOf", ignoreCase = true))
        val listed = export(dn, LdifExportOptions(scope = ExportScope.ENTRY, attributes = listOf("*", "memberOf"))).second
        assertTrue(listed.contains("memberOf") && !listed.contains("modifyTimestamp", ignoreCase = true))
        val included = export(dn, LdifExportOptions(scope = ExportScope.ENTRY, includeOperational = true)).second
        assertTrue(included.contains("modifyTimestamp", ignoreCase = true) && included.contains("entryUUID", ignoreCase = true))
    }

    @Test
    fun `round trip export delete import`() = runBlocking {
        val (_, ldif) = export("ou=people,$base", LdifExportOptions(header = "test export"))
        assertTrue(ldif.startsWith("# test export"))
        session.delete("ou=people,$base", recursive = true)
        val summary = LdifScript.run(session, StringReader(ldif), LdifRunOptions()) {}
        assertEquals(0, summary.failed)
        assertEquals(8, summary.succeeded)
        assertTrue(session.getEntry("uid=u3,ou=staff,ou=people,$base", listOf("1.1")) != null)
        Unit
    }

    @Test
    fun `out of order entries are reordered parents first`() {
        val entries = listOf(
            "uid=a,ou=x,ou=people,$base",
            "ou=x,ou=people,$base",
            "uid=b,ou=y,ou=people,$base",
            "ou=people,$base",
            "ou=y,ou=people,$base",
            "uid=orphan,ou=missing,ou=people,$base",
        ).map { com.unboundid.ldap.sdk.Entry(it) }
        val orderer = LdifExport.ParentFirstOrderer("ou=people,$base")
        val written = mutableListOf<String>()
        entries.forEach { orderer.accept(it) { e -> written += e.dn } }
        orderer.drain { written += it.dn }
        assertEquals(
            listOf(
                "ou=people,$base", "ou=x,ou=people,$base", "uid=a,ou=x,ou=people,$base",
                "ou=y,ou=people,$base", "uid=b,ou=y,ou=people,$base", "uid=orphan,ou=missing,ou=people,$base",
            ),
            written,
        )
    }
}
