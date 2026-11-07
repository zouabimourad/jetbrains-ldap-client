package com.mouradzouabi.ldapclient.core

import com.unboundid.ldap.sdk.Entry
import com.unboundid.ldap.sdk.schema.ObjectClassType

/** Builds LDIF skeletons for new entries from the server schema. */
object EntryTemplate {
    const val PLACEHOLDER = "new-entry"

    private val PREFERRED_RDN = listOf("cn", "uid", "ou", "o", "dc", "l", "c", "sn", "name")

    /** Selected object classes plus all their superiors, superiors first (`top` first of all). */
    fun objectClassChain(schema: LdapSchema, objectClasses: List<String>): List<String> {
        val s = schema.schema ?: return objectClasses
        val result = linkedSetOf<String>()
        fun visit(name: String) {
            val def = s.getObjectClass(name)
            if (def == null) {
                if (result.none { it.equals(name, true) }) result += name
                return
            }
            def.getSuperiorClasses(s, false).forEach { visit(it.nameOrOID) }
            if (result.none { it.equals(def.nameOrOID, true) }) result += def.nameOrOID
        }
        objectClasses.forEach(::visit)
        return result.sortedBy { if (it.equals("top", true)) 0 else 1 }
    }

    fun structuralObjectClasses(schema: LdapSchema): List<String> =
        schema.schema?.objectClasses?.filter { it.getObjectClassType(schema.schema) != ObjectClassType.ABSTRACT }
            ?.map { it.nameOrOID }?.sortedWith(String.CASE_INSENSITIVE_ORDER).orEmpty()

    fun rdnAttribute(schema: LdapSchema, objectClasses: List<String>): String {
        val required = schema.requiredAttributes(objectClasses).filter { !it.equals("objectClass", true) }
        val allowed = schema.allowedAttributes(objectClasses)
        return PREFERRED_RDN.firstOrNull { p -> required.any { it.equals(p, true) } }
            ?: required.firstOrNull()
            ?: PREFERRED_RDN.firstOrNull { p -> allowed.any { it.equals(p, true) } }
            ?: "cn"
    }

    /**
     * LDIF for a new entry below [parentDn]: the RDN attribute has a placeholder value, required attributes are empty
     * lines to fill in and optional attributes are listed as comments.
     */
    fun generate(schema: LdapSchema, objectClasses: List<String>, parentDn: String): String {
        val chain = objectClassChain(schema, objectClasses)
        val rdn = rdnAttribute(schema, chain)
        val required = schema.requiredAttributes(chain).filter { !it.equals("objectClass", true) && !it.equals(rdn, true) }
        val optional = (schema.allowedAttributes(chain) - schema.requiredAttributes(chain).toSet())
            .filter { !it.equals(rdn, true) && !schema.isOperational(it) }
        return buildString {
            append("dn: ").append(LdapDn.child("$rdn=$PLACEHOLDER", parentDn)).append('\n')
            chain.forEach { append("objectClass: ").append(it).append('\n') }
            append(rdn).append(": ").append(PLACEHOLDER).append('\n')
            required.forEach { append(it).append(": \n") }
            if (optional.isNotEmpty()) {
                append("# Optional attributes (remove the leading '# ' to use them):\n")
                optional.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { append("# ").append(it).append(": \n") }
            }
        }
    }

    /**
     * LDIF of a copy of [entry] (without operational attributes) to be created next to it. RDN values get [suffix]
     * so that the copy does not collide with the original.
     */
    fun duplicate(entry: Entry, schema: LdapSchema, suffix: String = "-copy"): String {
        val copy = Entry(entry.dn, entry.attributes.filter { !schema.isOperational(it.name) })
        val rdn = entry.parsedDN.rdn
        if (rdn != null && suffix.isNotEmpty()) {
            val names = rdn.attributeNames
            val values = rdn.attributeValues.map { it + suffix }.toTypedArray()
            names.forEachIndexed { i, name ->
                copy.removeAttributeValue(name, rdn.attributeValues[i])
                copy.addAttribute(name, values[i])
            }
            val parent = entry.parsedDN.parent
            copy.setDN(com.unboundid.ldap.sdk.DN(com.unboundid.ldap.sdk.RDN(names, values), parent ?: com.unboundid.ldap.sdk.DN.NULL_DN))
        }
        return copy.toLDIF(0).joinToString("\n") + "\n"
    }

    /** Names of attributes whose values are all empty, which the server would reject. */
    fun emptyAttributes(entry: Entry): List<String> =
        entry.attributes.filter { a -> a.valueByteArrays.all { it.isEmpty() } }.map { it.name }
}
