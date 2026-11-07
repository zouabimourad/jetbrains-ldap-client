package com.mouradzouabi.ldapclient.core

import com.unboundid.ldap.listener.InMemoryDirectoryServer
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig
import com.unboundid.ldap.listener.InMemoryListenerConfig
import com.unboundid.ldap.sdk.LDAPConnectionPool
import com.mouradzouabi.ldapclient.model.LdapConnectionConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GroupMembershipTest {
    private lateinit var server: InMemoryDirectoryServer
    private lateinit var session: LdapSession
    private val base = "dc=example,dc=com"
    private val alice = "uid=alice,ou=people,$base"
    private val bob = "uid=bob,ou=people,$base"

    @Before
    fun setUp() {
        // No schema checking so posixGroup / groupOfURLs can be used regardless of the bundled schema.
        server = InMemoryDirectoryServer(InMemoryDirectoryServerConfig(base).apply {
            setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", 0))
            schema = null
        })
        server.add("dn: $base", "objectClass: domain", "dc: example")
        server.add("dn: ou=people,$base", "objectClass: organizationalUnit", "ou: people")
        server.add("dn: ou=groups,$base", "objectClass: organizationalUnit", "ou: groups")
        server.add("dn: $alice", "objectClass: inetOrgPerson", "uid: alice", "cn: Alice", "sn: A")
        server.add("dn: $bob", "objectClass: inetOrgPerson", "uid: bob", "cn: Bob", "sn: B", "departmentNumber: 42")
        server.add("dn: cn=devs,ou=groups,$base", "objectClass: groupOfNames", "cn: devs", "member: $alice", "member: $bob")
        server.add("dn: cn=staff,ou=groups,$base", "objectClass: groupOfNames", "cn: staff", "member: cn=devs,ou=groups,$base")
        server.add("dn: cn=all,ou=groups,$base", "objectClass: groupOfNames", "cn: all", "member: cn=staff,ou=groups,$base")
        server.add("dn: cn=admins,ou=groups,$base", "objectClass: groupOfUniqueNames", "cn: admins", "uniqueMember: $alice")
        server.add("dn: cn=legacy,ou=groups,$base", "objectClass: groupOfUniqueNames", "cn: legacy", "uniqueMember: $bob#'0101'B")
        server.add("dn: cn=unix,ou=groups,$base", "objectClass: posixGroup", "cn: unix", "gidNumber: 100", "memberUid: alice", "memberUid: ghost")
        server.add("dn: cn=dept42,ou=groups,$base", "objectClass: groupOfURLs", "cn: dept42",
            "memberURL: ldap:///ou=people,$base??sub?(departmentNumber=42)")
        server.startListening()
        val config = LdapConnectionConfig().apply { port = server.listenPort; baseDn = base }
        val conn = server.connection
        session = LdapSession(config, LDAPConnectionPool(conn, 1, 3), conn.rootDSE)
    }

    @After
    fun tearDown() {
        session.close()
        server.shutDown(true)
    }

    @Test
    fun `groups of a user include direct unique posix and nested groups`() = runBlocking {
        val result = GroupMembership.groupsOf(session, alice)
        val byName = result.groups.associateBy { it.name }
        assertEquals(setOf("devs", "admins", "unix", "staff", "all"), byName.keys)
        assertNull(byName.getValue("devs").via)
        assertNull(byName.getValue("admins").via)
        assertNull(byName.getValue("unix").via)
        assertEquals("devs", byName.getValue("staff").via)
        assertEquals("staff", byName.getValue("all").via)
        assertTrue(!result.truncated)
    }

    @Test
    fun `direct only lookup`() = runBlocking {
        val names = GroupMembership.groupsOf(session, bob, nested = false).groups.map { it.name }.toSet()
        assertEquals(setOf("devs"), names)
    }

    @Test
    fun `members of static groups`() = runBlocking {
        val devs = GroupMembership.membersOf(session, "cn=devs,ou=groups,$base").members
        assertEquals(listOf(alice, bob), devs.map { it.dn })
        val admins = GroupMembership.membersOf(session, "cn=admins,ou=groups,$base").members
        assertEquals(listOf(alice), admins.map { it.dn })
        val legacy = GroupMembership.membersOf(session, "cn=legacy,ou=groups,$base").members
        assertEquals(listOf(bob), legacy.map { it.dn })
        val unix = GroupMembership.membersOf(session, "cn=unix,ou=groups,$base").members
        assertEquals(listOf("alice", "ghost"), unix.map { it.uid })
    }

    @Test
    fun `members of dynamic group and uid resolution`() = runBlocking {
        val dept = GroupMembership.membersOf(session, "cn=dept42,ou=groups,$base").members
        assertEquals(listOf(bob), dept.map { it.dn })
        assertEquals("memberURL", dept.single().source)
        assertEquals(alice, GroupMembership.resolveUid(session, "alice"))
        assertNull(GroupMembership.resolveUid(session, "ghost"))
    }

    @Test
    fun `helpers`() {
        assertEquals(alice, GroupMembership.memberDn("$alice#'0101'B"))
        assertEquals("alice", GroupMembership.rdnValue(alice))
        assertTrue(GroupMembership.isGroup(listOf("top", "groupOfUniqueNames")))
        assertTrue(GroupMembership.isUidMemberAttribute("memberUid"))
    }
}
