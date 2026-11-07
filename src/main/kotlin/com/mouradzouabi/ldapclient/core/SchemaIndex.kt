package com.mouradzouabi.ldapclient.core

import com.unboundid.ldap.sdk.schema.AttributeTypeDefinition
import com.unboundid.ldap.sdk.schema.ObjectClassDefinition
import com.unboundid.ldap.sdk.schema.Schema

enum class SchemaKind(val title: String, val linkPrefix: String) {
    OBJECT_CLASS("Object Classes", "oc"),
    ATTRIBUTE_TYPE("Attribute Types", "at"),
    MATCHING_RULE("Matching Rules", "mr"),
    SYNTAX("Syntaxes", "syn");

    companion object {
        fun ofPrefix(prefix: String) = entries.firstOrNull { it.linkPrefix == prefix }
    }
}

data class SchemaItem(val kind: SchemaKind, val oid: String, val names: List<String>, val description: String?, val obsolete: Boolean) {
    val label: String get() = names.firstOrNull() ?: description?.takeIf { kind == SchemaKind.SYNTAX } ?: oid

    fun matches(text: String): Boolean =
        text.isEmpty() || names.any { it.contains(text, true) } || oid.startsWith(text) || (kind == SchemaKind.SYNTAX && description?.contains(text, true) == true)
}

/** Sorted schema elements plus reverse references ("used by"), all keyed by lower-case OID. */
class SchemaIndex(val schema: Schema) {
    val objectClasses = schema.objectClasses.map { SchemaItem(SchemaKind.OBJECT_CLASS, it.oid, it.names.toList(), it.description, it.isObsolete) }.sortedBy { it.label.lowercase() }
    val attributeTypes = schema.attributeTypes.map { SchemaItem(SchemaKind.ATTRIBUTE_TYPE, it.oid, it.names.toList(), it.description, it.isObsolete) }.sortedBy { it.label.lowercase() }
    val matchingRules = schema.matchingRules.map { SchemaItem(SchemaKind.MATCHING_RULE, it.oid, it.names.toList(), it.description, it.isObsolete) }.sortedBy { it.label.lowercase() }
    /** Defined syntaxes plus the ones only referenced by attribute types (some servers do not publish ldapSyntaxes). */
    val syntaxes = run {
        val defined = schema.attributeSyntaxes.associateBy { it.oid.lowercase() }
        val referenced = schema.attributeTypes.mapNotNull { syntaxOid(it) }
        (defined.values.map { it.oid } + referenced).distinctBy { it.lowercase() }
            .map { SchemaItem(SchemaKind.SYNTAX, it, emptyList(), defined[it.lowercase()]?.description ?: KNOWN_SYNTAXES[it], false) }
            .sortedBy { it.label.lowercase() }
    }

    private val requiredIn = HashMap<String, MutableList<String>>()
    private val optionalIn = HashMap<String, MutableList<String>>()
    private val subclasses = HashMap<String, MutableList<String>>()
    private val subtypes = HashMap<String, MutableList<String>>()
    private val bySyntax = HashMap<String, MutableList<String>>()
    private val byMatchingRule = HashMap<String, MutableList<String>>()

    init {
        for (oc in schema.objectClasses) {
            oc.requiredAttributes.forEach { a -> attributeOid(a)?.let { requiredIn.getOrPut(it) { mutableListOf() } += oc.oid } }
            oc.optionalAttributes.forEach { a -> attributeOid(a)?.let { optionalIn.getOrPut(it) { mutableListOf() } += oc.oid } }
            oc.superiorClasses.forEach { s -> objectClassOid(s)?.let { subclasses.getOrPut(it) { mutableListOf() } += oc.oid } }
        }
        for (at in schema.attributeTypes) {
            at.superiorType?.let { attributeOid(it) }?.let { subtypes.getOrPut(it) { mutableListOf() } += at.oid }
            syntaxOid(at)?.let { bySyntax.getOrPut(it.lowercase()) { mutableListOf() } += at.oid }
            listOfNotNull(at.getEqualityMatchingRule(schema), at.getOrderingMatchingRule(schema), at.getSubstringMatchingRule(schema))
                .mapNotNull(::matchingRuleOid).distinct()
                .forEach { byMatchingRule.getOrPut(it) { mutableListOf() } += at.oid }
        }
    }

    fun items(kind: SchemaKind) = when (kind) {
        SchemaKind.OBJECT_CLASS -> objectClasses
        SchemaKind.ATTRIBUTE_TYPE -> attributeTypes
        SchemaKind.MATCHING_RULE -> matchingRules
        SchemaKind.SYNTAX -> syntaxes
    }

    /** Finds an element by name or OID. */
    fun find(kind: SchemaKind, nameOrOid: String): SchemaItem? {
        val oid = when (kind) {
            SchemaKind.OBJECT_CLASS -> objectClassOid(nameOrOid)
            SchemaKind.ATTRIBUTE_TYPE -> attributeOid(LdapSchema.baseName(nameOrOid))
            SchemaKind.MATCHING_RULE -> matchingRuleOid(nameOrOid)
            SchemaKind.SYNTAX -> nameOrOid.substringBefore('{').lowercase()
        } ?: return null
        return items(kind).firstOrNull { it.oid.equals(oid, true) }
    }

    fun requiredIn(attributeOid: String) = names(requiredIn[attributeOid.lowercase()], ::objectClassName)
    fun optionalIn(attributeOid: String) = names(optionalIn[attributeOid.lowercase()], ::objectClassName)
    fun subclasses(objectClassOid: String) = names(subclasses[objectClassOid.lowercase()], ::objectClassName)
    fun subtypes(attributeOid: String) = names(subtypes[attributeOid.lowercase()], ::attributeName)
    fun attributesWithSyntax(syntaxOid: String) = names(bySyntax[syntaxOid.lowercase()], ::attributeName)
    fun attributesWithMatchingRule(ruleOid: String) = names(byMatchingRule[ruleOid.lowercase()], ::attributeName)

    fun objectClass(oid: String): ObjectClassDefinition? = schema.getObjectClass(oid)
    fun attributeType(oid: String): AttributeTypeDefinition? = schema.getAttributeType(oid)

    fun syntaxOid(type: AttributeTypeDefinition): String? = type.getSyntaxOID(schema)?.substringBefore('{')

    private fun names(oids: List<String>?, name: (String) -> String) = oids.orEmpty().map(name).sortedWith(String.CASE_INSENSITIVE_ORDER)
    private fun objectClassName(oid: String) = schema.getObjectClass(oid)?.nameOrOID ?: oid
    private fun attributeName(oid: String) = schema.getAttributeType(oid)?.nameOrOID ?: oid

    private fun attributeOid(name: String) = schema.getAttributeType(name)?.oid?.lowercase()
    private fun objectClassOid(name: String) = schema.getObjectClass(name)?.oid?.lowercase()
    private fun matchingRuleOid(name: String) = schema.getMatchingRule(name)?.oid?.lowercase()

    companion object {
        /** Descriptions of the common RFC 4517 syntaxes, used when the server does not publish them. */
        val KNOWN_SYNTAXES = mapOf(
            "1.3.6.1.4.1.1466.115.121.1.3" to "Attribute Type Description",
            "1.3.6.1.4.1.1466.115.121.1.5" to "Binary",
            "1.3.6.1.4.1.1466.115.121.1.6" to "Bit String",
            "1.3.6.1.4.1.1466.115.121.1.7" to "Boolean",
            "1.3.6.1.4.1.1466.115.121.1.8" to "Certificate",
            "1.3.6.1.4.1.1466.115.121.1.9" to "Certificate List",
            "1.3.6.1.4.1.1466.115.121.1.11" to "Country String",
            "1.3.6.1.4.1.1466.115.121.1.12" to "DN",
            "1.3.6.1.4.1.1466.115.121.1.14" to "Delivery Method",
            "1.3.6.1.4.1.1466.115.121.1.15" to "Directory String",
            "1.3.6.1.4.1.1466.115.121.1.16" to "DIT Content Rule Description",
            "1.3.6.1.4.1.1466.115.121.1.17" to "DIT Structure Rule Description",
            "1.3.6.1.4.1.1466.115.121.1.22" to "Facsimile Telephone Number",
            "1.3.6.1.4.1.1466.115.121.1.23" to "Fax",
            "1.3.6.1.4.1.1466.115.121.1.24" to "Generalized Time",
            "1.3.6.1.4.1.1466.115.121.1.25" to "Guide",
            "1.3.6.1.4.1.1466.115.121.1.26" to "IA5 String",
            "1.3.6.1.4.1.1466.115.121.1.27" to "Integer",
            "1.3.6.1.4.1.1466.115.121.1.28" to "JPEG",
            "1.3.6.1.4.1.1466.115.121.1.30" to "Matching Rule Description",
            "1.3.6.1.4.1.1466.115.121.1.31" to "Matching Rule Use Description",
            "1.3.6.1.4.1.1466.115.121.1.34" to "Name And Optional UID",
            "1.3.6.1.4.1.1466.115.121.1.35" to "Name Form Description",
            "1.3.6.1.4.1.1466.115.121.1.36" to "Numeric String",
            "1.3.6.1.4.1.1466.115.121.1.37" to "Object Class Description",
            "1.3.6.1.4.1.1466.115.121.1.38" to "OID",
            "1.3.6.1.4.1.1466.115.121.1.39" to "Other Mailbox",
            "1.3.6.1.4.1.1466.115.121.1.40" to "Octet String",
            "1.3.6.1.4.1.1466.115.121.1.41" to "Postal Address",
            "1.3.6.1.4.1.1466.115.121.1.44" to "Printable String",
            "1.3.6.1.4.1.1466.115.121.1.50" to "Telephone Number",
            "1.3.6.1.4.1.1466.115.121.1.52" to "Telex Number",
            "1.3.6.1.4.1.1466.115.121.1.53" to "UTC Time",
            "1.3.6.1.4.1.1466.115.121.1.54" to "LDAP Syntax Description",
            "1.3.6.1.4.1.1466.115.121.1.58" to "Substring Assertion",
            "1.3.6.1.1.16.1" to "UUID",
        )
    }
}
