package com.mouradzouabi.ldapclient.core

import com.unboundid.ldap.sdk.Entry
import com.unboundid.ldap.sdk.Filter
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.LDAPURL
import com.unboundid.ldap.sdk.ResultCode
import com.unboundid.ldap.sdk.SearchScope

/** A group containing an entry. [via] is the name of the intermediate group for nested memberships. */
data class GroupRef(val dn: String, val name: String, val via: String? = null)

/** A group member: either a DN (member / uniqueMember / memberURL) or a bare uid (posixGroup memberUid). */
data class MemberRef(val dn: String?, val uid: String?, val source: String) {
    val label: String get() = uid ?: dn?.let(GroupMembership::rdnValue).orEmpty()
}

data class GroupsResult(val groups: List<GroupRef>, val truncated: Boolean)

data class MembersResult(val members: List<MemberRef>, val truncated: Boolean)

/**
 * Group membership lookups that work across common directory flavours:
 * groupOfNames (`member`), groupOfUniqueNames (`uniqueMember`), posixGroup (`memberUid`),
 * Active Directory (`member`, ranged retrieval), dynamic groups (`memberURL`) and `memberOf` / `isMemberOf`.
 */
object GroupMembership {
    val GROUP_CLASSES = setOf(
        "groupofnames", "groupofuniquenames", "posixgroup", "group", "groupofurls", "groupofentries", "groupofmembers",
    )
    private val DN_MEMBER_ATTRIBUTES = listOf("member", "uniqueMember")
    private const val UID_MEMBER_ATTRIBUTE = "memberUid"
    private val MEMBER_OF_ATTRIBUTES = listOf("memberOf", "isMemberOf")
    private val GROUP_ATTRIBUTES = listOf("cn", "objectClass")
    private val RANGE = Regex("^(.+);range=(\\d+)-(\\d+|\\*)$", RegexOption.IGNORE_CASE)
    private val UNIQUE_MEMBER_UID = Regex("#'[01]*'B$")

    const val MAX_NESTED_LOOKUPS = 500
    const val MAX_NESTING_DEPTH = 10

    fun isGroup(objectClasses: Collection<String>) = objectClasses.any { it.lowercase() in GROUP_CLASSES }

    fun isMemberAttribute(attribute: String) =
        LdapSchema.baseName(attribute).lowercase().let { it == "member" || it == "uniquemember" || it == "memberuid" }

    fun isUidMemberAttribute(attribute: String) = LdapSchema.baseName(attribute).equals(UID_MEMBER_ATTRIBUTE, ignoreCase = true)

    /** Strips the optional `#'0101'B` unique identifier suffix of uniqueMember (nameAndOptionalUID) values. */
    fun memberDn(value: String): String = value.replace(UNIQUE_MEMBER_UID, "")

    fun rdnValue(dn: String): String = LdapDn.parse(dn)?.rdn?.attributeValues?.firstOrNull() ?: LdapDn.rdn(dn)

    /**
     * Groups containing [dn]. Direct groups come first; with [nested] the group hierarchy is walked upwards
     * (bounded by [MAX_NESTING_DEPTH] levels and [MAX_NESTED_LOOKUPS] searches).
     */
    suspend fun groupsOf(session: LdapSession, dn: String, nested: Boolean = true, maxResults: Int = 5000): GroupsResult {
        val schema = session.schemaAsync()
        val entry = session.getEntry(dn, listOf("uid") + MEMBER_OF_ATTRIBUTES)
            ?: throw LDAPException(ResultCode.NO_SUCH_OBJECT, "Entry $dn does not exist")
        val dnAttributes = DN_MEMBER_ATTRIBUTES.filter { schema.knows(it) }
        val uids = entry.getAttributeValues("uid")?.toList().orEmpty()
        val uidAttribute = UID_MEMBER_ATTRIBUTE.takeIf { uids.isNotEmpty() && schema.knows(it) }

        val found = LinkedHashMap<String, GroupRef>()
        val self = LdapDn.normalize(dn)
        var truncated = false

        fun accept(group: GroupRef): Boolean {
            val key = LdapDn.normalize(group.dn)
            if (key == self || key in found) return false
            if (found.size >= maxResults) {
                truncated = true
                return false
            }
            found[key] = group
            return true
        }

        memberFilter(dnAttributes, dn, uidAttribute, uids)?.let { filter ->
            truncated = searchGroups(session, filter, maxResults) { accept(GroupRef(it.dn, groupName(it))) } || truncated
        }
        for (attribute in MEMBER_OF_ATTRIBUTES) {
            entry.getAttributeValues(attribute)?.forEach { accept(GroupRef(it, rdnValue(it))) }
        }

        if (nested && dnAttributes.isNotEmpty()) {
            var frontier = found.values.toList()
            var depth = 0
            var lookups = 0
            while (frontier.isNotEmpty() && depth < MAX_NESTING_DEPTH && !truncated) {
                val next = ArrayList<GroupRef>()
                for (group in frontier) {
                    if (lookups++ >= MAX_NESTED_LOOKUPS) {
                        truncated = true
                        break
                    }
                    val filter = memberFilter(dnAttributes, group.dn, null, emptyList()) ?: continue
                    truncated = searchGroups(session, filter, maxResults) {
                        val parent = GroupRef(it.dn, groupName(it), via = group.name)
                        if (accept(parent)) next += parent
                    } || truncated
                }
                frontier = next
                depth++
            }
        }
        return GroupsResult(found.values.toList(), truncated)
    }

    /** Members of [groupDn], including ranged (AD) values and dynamic `memberURL` members. */
    suspend fun membersOf(session: LdapSession, groupDn: String, maxResults: Int = 100_000): MembersResult {
        val requested = DN_MEMBER_ATTRIBUTES + UID_MEMBER_ATTRIBUTE + "memberURL"
        val entry = session.getEntry(groupDn, requested)
            ?: throw LDAPException(ResultCode.NO_SUCH_OBJECT, "Entry $groupDn does not exist")
        val members = LinkedHashMap<String, MemberRef>()
        var truncated = false

        fun add(ref: MemberRef) {
            if (members.size >= maxResults) {
                truncated = true
                return
            }
            val key = ref.dn?.let { "dn:" + LdapDn.normalize(it) } ?: "uid:${ref.uid}"
            members.putIfAbsent(key, ref)
        }

        fun addValue(attribute: String, value: String) {
            if (isUidMemberAttribute(attribute)) add(MemberRef(null, value, UID_MEMBER_ATTRIBUTE))
            else add(MemberRef(memberDn(value), null, attribute))
        }

        for (attribute in entry.attributes) {
            val range = RANGE.matchEntire(attribute.name)
            val name = range?.groupValues?.get(1) ?: attribute.name
            if (name.equals("memberURL", ignoreCase = true)) continue
            attribute.values.forEach { addValue(name, it) }
            if (range != null) truncated = loadRemainingRange(session, groupDn, name, range.groupValues[3]) { addValue(name, it) } || truncated
        }
        for (url in entry.getAttributeValues("memberURL").orEmpty()) {
            if (members.size >= maxResults) break
            truncated = searchMemberUrl(session, url, maxResults - members.size) { add(MemberRef(it, null, "memberURL")) } || truncated
        }
        return MembersResult(members.values.toList(), truncated)
    }

    /** Finds the entry of a posixGroup `memberUid` value. */
    suspend fun resolveUid(session: LdapSession, uid: String): String? {
        val filter = Filter.createEqualityFilter("uid", uid)
        for (base in session.baseDns) {
            var dn: String? = null
            session.search(SearchSpec(base, SearchScope.SUB, filter, listOf("1.1")), 1) { dn = it.dn }
            if (dn != null) return dn
        }
        return null
    }

    // ------------------------------------------------------------------ internals

    private fun memberFilter(dnAttributes: List<String>, dn: String, uidAttribute: String?, uids: List<String>): Filter? {
        val parts = dnAttributes.map { Filter.createEqualityFilter(it, dn) } +
            (uidAttribute?.let { attr -> uids.map { Filter.createEqualityFilter(attr, it) } }.orEmpty())
        return when (parts.size) {
            0 -> null
            1 -> parts.single()
            else -> Filter.createORFilter(parts)
        }
    }

    /** Returns true when the result was truncated. */
    private suspend fun searchGroups(session: LdapSession, filter: Filter, maxResults: Int, onGroup: (Entry) -> Unit): Boolean {
        var truncated = false
        for (base in session.baseDns) {
            val outcome = session.search(SearchSpec(base, SearchScope.SUB, filter, GROUP_ATTRIBUTES), maxResults) { onGroup(it) }
            truncated = truncated || outcome.truncated
        }
        return truncated
    }

    /** AD returns at most ~1500 values per request (`member;range=0-1499`); fetches the remaining chunks. */
    private suspend fun loadRemainingRange(
        session: LdapSession,
        dn: String,
        attribute: String,
        firstHigh: String,
        onValue: (String) -> Unit,
    ): Boolean {
        var high = firstHigh
        var chunks = 0
        while (high != "*") {
            if (++chunks > 1000) return true
            val start = high.toLong() + 1
            val entry = session.getEntry(dn, listOf("$attribute;range=$start-*")) ?: return false
            val ranged = entry.attributes.firstOrNull { RANGE.matchEntire(it.name)?.groupValues?.get(1).equals(attribute, true) }
                ?: return false
            ranged.values.forEach(onValue)
            high = RANGE.matchEntire(ranged.name)!!.groupValues[3]
        }
        return false
    }

    private suspend fun searchMemberUrl(session: LdapSession, url: String, maxResults: Int, onDn: (String) -> Unit): Boolean {
        val ldapUrl = try {
            LDAPURL(url)
        } catch (_: LDAPException) {
            return false
        }
        val spec = SearchSpec(ldapUrl.baseDN.toString(), ldapUrl.scope, ldapUrl.filter, listOf("1.1"))
        return session.search(spec, maxResults) { onDn(it.dn) }.truncated
    }

    private fun groupName(entry: Entry) = entry.getAttributeValue("cn") ?: rdnValue(entry.dn)

    /** Unknown or empty schema (not readable / not published) means "assume it exists". */
    private fun LdapSchema.knows(attribute: String) =
        schema.let { it == null || it.attributeTypes.isEmpty() } || attributeType(attribute) != null
}
