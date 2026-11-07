package com.mouradzouabi.ldapclient.core

import com.unboundid.ldap.listener.InMemoryDirectoryServer
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig
import com.unboundid.ldap.listener.InMemoryListenerConfig
import com.unboundid.ldap.sdk.Entry
import com.unboundid.ldap.sdk.LDAPConnectionPool
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.Modification
import com.unboundid.ldap.sdk.ModificationType
import com.unboundid.ldif.LDIFReader
import com.unboundid.ldif.LDIFModifyChangeRecord
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
import java.io.ByteArrayInputStream
import java.time.Instant

class FeatureCoreTest {
    private val base = "dc=example,dc=com"
    private lateinit var server: InMemoryDirectoryServer
    private lateinit var server2: InMemoryDirectoryServer
    private lateinit var session: LdapSession
    private lateinit var session2: LdapSession

    private fun start(id: String): Pair<InMemoryDirectoryServer, LdapSession> {
        val cfg = InMemoryDirectoryServerConfig(base).apply {
            setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", 0))
        }
        val s = InMemoryDirectoryServer(cfg)
        s.add("dn: $base", "objectClass: top", "objectClass: domain", "dc: example")
        s.startListening()
        val config = LdapConnectionConfig().apply { this.id = id; name = id; port = s.listenPort; baseDn = base; pageSize = 10 }
        val c = s.connection
        return s to LdapSession(config, LDAPConnectionPool(c, 1, 4), c.rootDSE)
    }

    @Before
    fun setUp() {
        start("one").let { server = it.first; session = it.second }
        start("two").let { server2 = it.first; session2 = it.second }
        server.add("dn: ou=src,$base", "objectClass: organizationalUnit", "ou: src")
        server.add("dn: ou=a,ou=src,$base", "objectClass: organizationalUnit", "ou: a", "description: A")
        for (i in 0 until 25) {
            server.add("dn: uid=u$i,ou=a,ou=src,$base", "objectClass: inetOrgPerson", "uid: u$i", "cn: U $i", "sn: U",
                "userPassword: secret$i")
        }
        server.add("dn: ou=b,ou=a,ou=src,$base", "objectClass: organizationalUnit", "ou: b")
        server.add("dn: cn=leaf,ou=b,ou=a,ou=src,$base", "objectClass: device", "cn: leaf")
        server.add("dn: ou=dst,$base", "objectClass: organizationalUnit", "ou: dst")
    }

    @After
    fun tearDown() {
        session.close(); session2.close()
        server.shutDown(true); server2.shutDown(true)
    }

    @Test
    fun `operations are reported with masked passwords`() = runBlocking {
        val operations = mutableListOf<LdapOperation>()
        session.operationListener = LdapOperationListener { operations += it }
        session.modify("uid=u1,ou=a,ou=src,$base", listOf(Modification(ModificationType.REPLACE, "userPassword", "topsecret")))
        try {
            session.delete("ou=missing,$base", recursive = false)
        } catch (_: LDAPException) {
        }
        assertEquals(2, operations.size)
        assertTrue(operations[0].succeeded)
        assertEquals("modify", operations[0].type)
        assertFalse(operations[0].ldif.contains("topsecret"))
        assertTrue(operations[0].ldif.contains(ChangeRecords.MASK))
        assertFalse(operations[1].succeeded)
        assertEquals("delete", operations[1].type)
    }

    @Test
    fun `masked add record hides passwords`() {
        val ldif = ChangeRecords.toLdif(com.unboundid.ldif.LDIFAddChangeRecord(Entry("cn=x,$base", listOf(
            com.unboundid.ldap.sdk.Attribute("cn", "x"), com.unboundid.ldap.sdk.Attribute("userPassword", "p1", "p2"),
        ))))
        assertFalse(ldif.contains("p1"))
        assertEquals(2, Regex("userPassword: \\*+").findAll(ldif).count())
    }

    @Test
    fun `subtree copy to another server with a new rdn`() = runBlocking {
        session2.add(Entry("ou=dst,$base", listOf(com.unboundid.ldap.sdk.Attribute("objectClass", "organizationalUnit"),
            com.unboundid.ldap.sdk.Attribute("ou", "dst"))))
        val result = SubtreeCopy(session, session2, "ou=a,ou=src,$base", "ou=copy,ou=dst,$base", true, ConflictPolicy.FAIL, false).run()
        assertTrue(result.failures.toString(), result.succeeded)
        assertEquals(28, result.copied)
        val root = session2.getEntry("ou=copy,ou=dst,$base", listOf("*"))!!
        assertEquals(listOf("copy"), root.getAttributeValues("ou").toList())
        assertEquals("A", root.getAttributeValue("description"))
        assertNotNull(session2.getEntry("cn=leaf,ou=b,ou=copy,ou=dst,$base", listOf("1.1")))
        assertEquals("secret3", session2.getEntry("uid=u3,ou=copy,ou=dst,$base", listOf("userPassword"))!!.getAttributeValue("userPassword"))
    }

    @Test
    fun `subtree copy conflicts`() = runBlocking {
        server.add("dn: ou=a,ou=dst,$base", "objectClass: organizationalUnit", "ou: a", "description: old")
        val skip = SubtreeCopy(session, session, "ou=a,ou=src,$base", "ou=a,ou=dst,$base", true, ConflictPolicy.SKIP, false).run()
        assertEquals(1, skip.skipped)
        assertEquals(27, skip.copied)
        val update = SubtreeCopy(session, session, "ou=a,ou=src,$base", "ou=a,ou=dst,$base", false, ConflictPolicy.UPDATE, false).run()
        assertEquals(1, update.updated)
        assertEquals("A", session.getEntry("ou=a,ou=dst,$base", listOf("*"))!!.getAttributeValue("description"))
        val fail = SubtreeCopy(session, session, "ou=a,ou=src,$base", "ou=a,ou=dst,$base", true, ConflictPolicy.FAIL, true).run()
        assertTrue(fail.stopped)
        assertEquals(1, fail.failures.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cannot copy into own subtree`() {
        SubtreeCopy(session, session, "ou=src,$base", "ou=x,ou=a,ou=src,$base", true, ConflictPolicy.FAIL, false)
    }

    @Test
    fun `move below a new parent`() = runBlocking {
        session.move("ou=b,ou=a,ou=src,$base", "ou=b", "ou=dst,$base")
        assertNotNull(session.getEntry("cn=leaf,ou=b,ou=dst,$base", listOf("1.1")))
        assertNull(session.getEntry("ou=b,ou=a,ou=src,$base", listOf("1.1")))
    }

    @Test
    fun `rebase dn`() {
        assertEquals("cn=x,ou=b,ou=new,dc=y", LdapDnMove.rebase("cn=x,ou=b,ou=a,dc=x", "ou=a,dc=x", "ou=new,dc=y"))
        assertTrue(LdapDnMove.isSameOrBelow("CN=X,OU=A,DC=X", "ou=a,dc=x"))
        assertFalse(LdapDnMove.isSameOrBelow("ou=a,dc=x", "cn=x,ou=a,dc=x"))
    }

    @Test
    fun `active directory account status`() {
        val now = Instant.parse("2025-06-01T00:00:00Z")
        val lockout = ticks(Instant.parse("2025-05-31T23:50:00Z"))
        val entry = Entry("cn=u,$base", listOf(
            com.unboundid.ldap.sdk.Attribute("objectClass", "user"),
            com.unboundid.ldap.sdk.Attribute("userAccountControl", (0x200 or 0x2).toString()),
            com.unboundid.ldap.sdk.Attribute("lockoutTime", lockout.toString()),
            com.unboundid.ldap.sdk.Attribute("pwdLastSet", "0"),
            com.unboundid.ldap.sdk.Attribute("accountExpires", "9223372036854775807"),
        ))
        val policy = Entry(base, listOf(com.unboundid.ldap.sdk.Attribute("lockoutDuration", (-30L * 60 * 10_000_000).toString())))
        val flavor = AccountTools.flavor(entry)
        assertEquals(AccountFlavor.ACTIVE_DIRECTORY, flavor)
        val status = AccountTools.analyze(entry, policy, flavor, now)
        assertTrue(status.disabled)
        assertTrue(status.locked)
        assertTrue(status.mustChangePassword)
        assertFalse(status.accountExpired)
        assertFalse(AccountTools.analyze(entry, policy, flavor, now.plusSeconds(3600)).locked)
        assertEquals("512", AccountTools.setDisabled(entry, flavor, false).single().values.single())
        assertEquals("0", AccountTools.unlock(entry, flavor).single().values.single())
    }

    @Test
    fun `ppolicy and 389 account status`() {
        val disabled = Entry("uid=u,$base", listOf(com.unboundid.ldap.sdk.Attribute("pwdAccountLockedTime", AccountTools.PPOLICY_PERMANENT_LOCK),
            com.unboundid.ldap.sdk.Attribute("pwdReset", "TRUE")))
        val flavor = AccountTools.flavor(disabled, "OpenLDAP")
        assertEquals(AccountFlavor.PPOLICY, flavor)
        val status = AccountTools.analyze(disabled, null, flavor)
        assertTrue(status.disabled && !status.locked && status.mustChangePassword)
        assertEquals(ModificationType.DELETE, AccountTools.unlock(disabled, flavor).single().modificationType)

        val ds = Entry("uid=u,$base", listOf(com.unboundid.ldap.sdk.Attribute("nsAccountLock", "true"),
            com.unboundid.ldap.sdk.Attribute("passwordExpirationTime", "20000101000000Z")))
        assertEquals(AccountFlavor.DS389, AccountTools.flavor(ds))
        val dsStatus = AccountTools.analyze(ds, null, AccountFlavor.DS389)
        assertTrue(dsStatus.disabled && dsStatus.passwordExpired)
        assertEquals("TRUE", AccountTools.forcePasswordChange(AccountFlavor.PPOLICY).single().values.single())
    }

    @Test
    fun `template from schema`() {
        val schema = LdapSchema(server.schema)
        val ldif = EntryTemplate.generate(schema, listOf("inetOrgPerson"), "ou=dst,$base")
        assertTrue(ldif, ldif.startsWith("dn: cn=${EntryTemplate.PLACEHOLDER},ou=dst,$base\nobjectClass: top\n"))
        assertTrue(ldif.contains("objectClass: person\n"))
        assertTrue(ldif.contains("\nsn: \n"))
        assertTrue(ldif.contains("# mail: \n"))
        val entry = LDIFReader.decodeEntry(*ldif.lines().filter { it.isNotBlank() }.toTypedArray())
        assertEquals(listOf("sn"), EntryTemplate.emptyAttributes(entry))
        assertTrue(EntryTemplate.structuralObjectClasses(schema).contains("inetOrgPerson"))
    }

    @Test
    fun `canonical ldif is sorted and masks passwords`() {
        val a = Entry("uid=u,$base", listOf(com.unboundid.ldap.sdk.Attribute("sn", "b", "a"),
            com.unboundid.ldap.sdk.Attribute("objectClass", "person"), com.unboundid.ldap.sdk.Attribute("userPassword", "x")))
        val text = EntryText.canonical(a)
        assertTrue(text, text.startsWith("dn: uid=u,$base\nobjectClass: person\nsn: a\nsn: b\nuserPassword: ${ChangeRecords.MASK}"))
        assertFalse(text.contains("userPassword: x"))
        val other = EntryText.canonical(Entry("uid=u,$base", listOf(com.unboundid.ldap.sdk.Attribute("userPassword", "y"))))
        assertFalse(other.substringAfter("userPassword") == text.substringAfter("userPassword"))
    }

    @Test
    fun `bulk modify script parses`() {
        val script = BulkModify.script(listOf("uid=a,$base", "uid=b,$base"), BulkOperation.ADD_VALUES, "description", listOf("x", "y"), listOf("Bulk edit"))
        val reader = LDIFReader(ByteArrayInputStream(script.toByteArray()))
        val first = reader.readChangeRecord() as LDIFModifyChangeRecord
        val second = reader.readChangeRecord() as LDIFModifyChangeRecord
        assertNull(reader.readChangeRecord())
        assertEquals("uid=a,$base", first.dn)
        assertEquals(listOf("x", "y"), second.modifications.single().values.toList())
    }

    private fun ticks(instant: Instant): Long =
        (instant.epochSecond - Instant.parse("1601-01-01T00:00:00Z").epochSecond) * 10_000_000
}

class SchemaIndexTest {
    @Test
    fun `reverse references`() {
        val schema = com.unboundid.ldap.sdk.schema.Schema.getDefaultStandardSchema()
        val index = SchemaIndex(schema)
        val sn = index.find(SchemaKind.ATTRIBUTE_TYPE, "SN")!!
        assertEquals("sn", sn.label)
        assertTrue(index.requiredIn(sn.oid).contains("person"))
        val person = index.find(SchemaKind.OBJECT_CLASS, "person")!!
        assertTrue(index.subclasses(person.oid).contains("organizationalPerson"))
        val dnSyntax = index.find(SchemaKind.SYNTAX, "1.3.6.1.4.1.1466.115.121.1.12")!!
        assertTrue(index.attributesWithSyntax(dnSyntax.oid).contains("member"))
        val name = index.find(SchemaKind.ATTRIBUTE_TYPE, "name")!!
        assertTrue(index.subtypes(name.oid).contains("cn"))
        assertTrue(index.objectClasses.size > 10)
    }
}

class EntryTemplateDuplicateTest {
    @org.junit.Test
    fun duplicateRenamesRdn() {
        val entry = com.unboundid.ldap.sdk.Entry(
            "uid=jdoe,ou=People,dc=example,dc=com",
            com.unboundid.ldap.sdk.Attribute("objectClass", "top", "inetOrgPerson"),
            com.unboundid.ldap.sdk.Attribute("uid", "jdoe"),
            com.unboundid.ldap.sdk.Attribute("cn", "John"),
            com.unboundid.ldap.sdk.Attribute("modifyTimestamp", "20240101000000Z"),
        )
        val ldif = EntryTemplate.duplicate(entry, LdapSchema(com.unboundid.ldap.sdk.schema.Schema.getDefaultStandardSchema()))
        val copy = com.unboundid.ldif.LDIFReader.decodeEntry(*ldif.lines().filter { it.isNotBlank() }.toTypedArray())
        org.junit.Assert.assertEquals("uid=jdoe-copy,ou=People,dc=example,dc=com", copy.dn)
        org.junit.Assert.assertEquals(listOf("jdoe-copy"), copy.getAttributeValues("uid").toList())
        org.junit.Assert.assertEquals("John", copy.getAttributeValue("cn"))
        org.junit.Assert.assertFalse(copy.hasAttribute("modifyTimestamp"))
    }
}
