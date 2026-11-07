package com.mouradzouabi.ldapclient.core

import com.unboundid.ldap.listener.InMemoryDirectoryServer
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig
import com.unboundid.ldap.listener.InMemoryListenerConfig
import com.unboundid.ldap.sdk.LDAPConnectionPool
import com.mouradzouabi.ldapclient.model.LdapConnectionConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class LdifScriptTest {
    private lateinit var server: InMemoryDirectoryServer
    private lateinit var session: LdapSession
    private val base = "dc=example,dc=com"

    @Before
    fun setUp() {
        server = InMemoryDirectoryServer(InMemoryDirectoryServerConfig(base).apply {
            setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", 0))
        })
        server.add("dn: $base", "objectClass: domain", "dc: example")
        server.add("dn: ou=people,$base", "objectClass: organizationalUnit", "ou: people")
        server.add("dn: uid=old,ou=people,$base", "objectClass: inetOrgPerson", "uid: old", "cn: Old", "sn: Old")
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

    private fun run(script: String, options: LdifRunOptions = LdifRunOptions()): Pair<LdifRunSummary, List<LdifRecordResult>> =
        runBlocking {
            val results = mutableListOf<LdifRecordResult>()
            val summary = LdifScript.run(session, StringReader(script.trimIndent()), options) { results += it }
            summary to results
        }

    private fun exists(dn: String) = runBlocking { session.getEntry(dn, listOf("1.1")) != null }

    @Test
    fun `chunks skip comments and version and keep line numbers`() {
        val text = """
            |version: 1
            |# a comment
            | continued comment
            |dn: cn=a,dc=x
            |cn: a
            |
            |
            |# another
            |dn: cn=b,dc=x
            |description: folded
            |  value
        """.trimMargin()
        val chunks = LdifScript.chunks(BufferedReader(StringReader(text))).toList()
        assertEquals(2, chunks.size)
        assertEquals(3, chunks[0].line)
        assertEquals(listOf("dn: cn=a,dc=x", "cn: a"), chunks[0].lines)
        assertEquals(8, chunks[1].line)
        assertEquals("folded value", (LdifScript.parse(chunks[1]) as com.unboundid.ldap.sdk.Entry).getAttributeValue("description"))
    }

    @Test
    fun `all change types are applied`() {
        val (summary, results) = run(
            """
            version: 1

            # content record => add
            dn: uid=new,ou=people,dc=example,dc=com
            objectClass: inetOrgPerson
            uid: new
            cn: New
            sn: New

            dn: uid=new,ou=people,dc=example,dc=com
            changetype: modify
            replace: sn
            sn: Newer
            -
            add: mail
            mail: new@example.com
            -

            dn: uid=new,ou=people,dc=example,dc=com
            changetype: modrdn
            newrdn: uid=renamed
            deleteoldrdn: 1

            dn: uid=old,ou=people,dc=example,dc=com
            changetype: delete

            dn: ou=groups,dc=example,dc=com
            changetype: add
            objectClass: organizationalUnit
            ou: groups
            """,
        )
        assertEquals(LdifRunSummary(5, 5, 0, false), summary)
        assertEquals(listOf("add", "modify", "moddn", "delete", "add"), results.map { it.operation })
        assertEquals(listOf(3, 9, 18, 23, 26), results.map { it.line })
        assertEquals("uid=renamed,ou=people,dc=example,dc=com", results[2].newDn)
        val renamed = runBlocking { session.getEntry("uid=renamed,ou=people,$base", listOf("*")) }!!
        assertEquals("Newer", renamed.getAttributeValue("sn"))
        assertEquals("new@example.com", renamed.getAttributeValue("mail"))
        assertFalse(exists("uid=old,ou=people,$base"))
        assertTrue(exists("ou=groups,$base"))
    }

    @Test
    fun `errors are reported and processing continues or stops`() {
        val script = """
            dn: uid=old,ou=people,dc=example,dc=com
            objectClass: inetOrgPerson
            uid: old
            cn: Old
            sn: Old

            dn: ou=a,dc=example,dc=com
            changetype: add
            objectClass: organizationalUnit
            ou: a
            """
        val (summary, results) = run(script)
        assertEquals(LdifRunSummary(2, 1, 1, false), summary)
        assertEquals(LdifStatus.FAILED, results[0].status)
        assertTrue(results[0].message, results[0].message!!.contains("already exists"))
        assertTrue(exists("ou=a,$base"))

        val (stopped, stoppedResults) = run(script.replace("ou=a", "ou=b").replace("ou: a", "ou: b"), LdifRunOptions(continueOnError = false))
        assertTrue(stopped.stoppedOnError)
        assertEquals(1, stoppedResults.size)
        assertFalse(exists("ou=b,$base"))
    }

    @Test
    fun `content records can replace existing entries`() {
        val (summary, results) = run(
            """
            dn: uid=old,ou=people,dc=example,dc=com
            objectClass: inetOrgPerson
            uid: old
            cn: Replaced
            sn: Old
            """,
            LdifRunOptions(contentMode = ContentRecordMode.ADD_OR_REPLACE),
        )
        assertEquals(results.toString(), 0, summary.failed)
        assertEquals("replace", results.single().operation)
        assertEquals("Replaced", runBlocking { session.getEntry("uid=old,ou=people,$base", listOf("cn")) }!!.getAttributeValue("cn"))
    }

    @Test
    fun `parse errors point to the record and dry run sends nothing`() {
        val (summary, results) = run(
            """
            dn: ou=valid,dc=example,dc=com
            changetype: add
            objectClass: organizationalUnit
            ou: valid

            dn: ou=broken,dc=example,dc=com
            changetype: frobnicate
            """,
            LdifRunOptions(dryRun = true),
        )
        assertEquals(1, summary.failed)
        assertEquals(LdifStatus.VALIDATED, results[0].status)
        assertEquals("parse", results[1].operation)
        assertEquals("ou=broken,dc=example,dc=com", results[1].dn)
        assertTrue(results[1].line >= 5)
        assertNull(runBlocking { session.getEntry("ou=valid,$base", listOf("1.1")) })
    }

    @Test
    fun `read only connections reject scripts`() {
        session.config.readOnly = true
        val (summary, _) = run(
            """
            dn: uid=old,ou=people,dc=example,dc=com
            changetype: delete
            """,
        )
        assertEquals(1, summary.failed)
        assertNotNull(runBlocking { session.getEntry("uid=old,ou=people,$base", listOf("1.1")) })
    }
}
