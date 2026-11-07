package com.mouradzouabi.ldapclient.core

import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.RDN

object LdapDn {
    fun parse(dn: String): DN? = try { DN(dn) } catch (_: LDAPException) { null }

    fun same(a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return true
        val da = parse(a) ?: return false
        val db = parse(b) ?: return false
        return da == db
    }

    fun normalize(dn: String): String = parse(dn)?.toNormalizedString() ?: dn.trim().lowercase()

    fun parent(dn: String): String? = parse(dn)?.parentString

    /** The leftmost RDN for display, or the full DN if it cannot be parsed. */
    fun rdn(dn: String): String = parse(dn)?.rdnString ?: dn

    fun rdnPairs(dn: String): List<Pair<String, String>> {
        val rdn = parse(dn)?.rdn ?: return emptyList()
        return rdn.attributeNames.zip(rdn.attributeValues)
    }

    fun isValidRdn(rdn: String): Boolean = RDN.isValidRDN(rdn)

    fun child(rdn: String, parentDn: String): String = if (parentDn.isEmpty()) rdn else "$rdn,$parentDn"

    fun depth(dn: String): Int = parse(dn)?.rdNs?.size ?: dn.count { it == ',' } + 1
}

object LdapErrors {
    fun describe(t: Throwable): String = when (t) {
        is LDAPException -> buildString {
            append(t.resultCode.name)
            val diag = t.diagnosticMessage
            if (!diag.isNullOrBlank()) append(": ").append(diag)
            else if (!t.message.isNullOrBlank() && t.message != t.resultCode.name) append(": ").append(t.message)
            t.matchedDN?.takeIf { it.isNotBlank() }?.let { append(" (matched DN: ").append(it).append(')') }
        }
        else -> t.message ?: t.javaClass.simpleName
    }
}

/** DN helpers needed to relocate entries. */
object LdapDnMove {
    /** Replaces the [oldBase] suffix of [dn] by [newBase]. */
    fun rebase(dn: String, oldBase: String, newBase: String): String {
        val rdns = DN(dn).rdNs
        val baseSize = DN(oldBase).rdNs.size
        require(rdns.size >= baseSize) { "$dn is not below $oldBase" }
        return DN(rdns.take(rdns.size - baseSize) + DN(newBase).rdNs).toString()
    }

    fun isSameOrBelow(dn: String, ancestor: String): Boolean {
        val d = LdapDn.parse(dn) ?: return false
        val a = LdapDn.parse(ancestor) ?: return false
        return d.isDescendantOf(a, true)
    }
}
