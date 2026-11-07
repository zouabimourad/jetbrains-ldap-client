package com.mouradzouabi.ldapclient.core

import com.unboundid.ldap.sdk.schema.AttributeTypeDefinition
import com.unboundid.ldap.sdk.schema.Schema

/** Null-safe view on the (optional) server schema. */
class LdapSchema(val schema: Schema?) {

    fun attributeType(name: String): AttributeTypeDefinition? = schema?.getAttributeType(baseName(name))

    fun isOperational(name: String): Boolean =
        attributeType(name)?.isOperational ?: (baseName(name).lowercase() in KNOWN_OPERATIONAL)

    fun isSingleValued(name: String): Boolean = attributeType(name)?.isSingleValued ?: false

    fun syntaxOid(name: String): String? {
        val type = attributeType(name) ?: return null
        return type.getSyntaxOID(schema)?.substringBefore('{')
    }

    val attributeNames: List<String> by lazy {
        schema?.userAttributeTypes?.map { it.nameOrOID }?.sortedWith(String.CASE_INSENSITIVE_ORDER).orEmpty()
    }

    val objectClassNames: List<String> by lazy {
        schema?.objectClasses?.map { it.nameOrOID }?.sortedWith(String.CASE_INSENSITIVE_ORDER).orEmpty()
    }

    fun requiredAttributes(objectClasses: Collection<String>): Set<String> =
        attributesOf(objectClasses, required = true)

    fun allowedAttributes(objectClasses: Collection<String>): Set<String> =
        attributesOf(objectClasses, required = true) + attributesOf(objectClasses, required = false)

    /**
     * OIDs of the attribute types allowed by [objectClasses], including their superiors and the auxiliary classes and
     * attributes granted by DIT content rules (Active Directory). Null when it cannot be determined.
     */
    fun allowedAttributeOids(objectClasses: Collection<String>): Set<String>? {
        val s = schema ?: return null
        if (objectClasses.isEmpty() || objectClasses.any { it.equals("extensibleObject", true) }) return null
        val classes = objectClasses.toMutableSet()
        val extra = mutableListOf<String>()
        for (oc in objectClasses) {
            val rule = s.getObjectClass(oc)?.oid?.let { s.getDITContentRule(it) } ?: continue
            rule.auxiliaryClasses?.let { classes += it }
            rule.requiredAttributes?.let { extra += it }
            rule.optionalAttributes?.let { extra += it }
        }
        if (classes.any { s.getObjectClass(it) == null }) return null
        return (allowedAttributes(classes) + extra).mapNotNullTo(HashSet()) { attributeType(it)?.oid?.lowercase() }
    }

    /** False when [attribute] is unknown or not allowed by [allowedOids] (see [allowedAttributeOids]). */
    fun isAllowed(attribute: String, allowedOids: Set<String>?): Boolean {
        if (allowedOids == null || isOperational(attribute)) return true
        val type = attributeType(attribute) ?: return false
        return type.oid.lowercase() in allowedOids
    }

    private fun attributesOf(objectClasses: Collection<String>, required: Boolean): Set<String> {
        val s = schema ?: return emptySet()
        val result = sortedSetOf(String.CASE_INSENSITIVE_ORDER)
        for (oc in objectClasses) {
            val def = s.getObjectClass(oc) ?: continue
            val attrs = if (required) def.getRequiredAttributes(s, true) else def.getOptionalAttributes(s, true)
            attrs.mapTo(result) { it.nameOrOID }
        }
        return result
    }

    companion object {
        fun baseName(name: String): String = name.substringBefore(';')

        val KNOWN_OPERATIONAL = setOf(
            "createtimestamp", "modifytimestamp", "creatorsname", "modifiersname", "entryuuid", "entrydn",
            "entrycsn", "hassubordinates", "numsubordinates", "subschemasubentry", "structuralobjectclass",
            "contextcsn", "memberof", "pwdchangedtime", "pwdaccountlockedtime", "pwdfailuretime", "pwdhistory",
            "pwdpolicysubentry", "ds-entry-unique-id", "nsuniqueid", "ismemberof", "aci", "governingstructurerule",
            "subordinatecount", "whencreated", "whenchanged", "usncreated", "usnchanged",
        )
    }
}
