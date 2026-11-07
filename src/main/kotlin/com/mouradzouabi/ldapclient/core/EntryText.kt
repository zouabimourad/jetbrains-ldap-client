package com.mouradzouabi.ldapclient.core

import com.unboundid.ldap.sdk.Attribute
import com.unboundid.ldap.sdk.Entry
import com.unboundid.ldap.sdk.Modification
import com.unboundid.ldap.sdk.ModificationType
import com.unboundid.ldif.LDIFModifyChangeRecord
import java.security.MessageDigest

/** Canonical LDIF renderings used to compare entries and to build scripts. */
object EntryText {
    /**
     * LDIF of [entry] with objectClass first, the other attributes and all values sorted, so that two entries can be
     * compared line by line. Password values are replaced by a short digest: differences stay visible, secrets do not.
     */
    fun canonical(entry: Entry, schema: LdapSchema? = null, includeOperational: Boolean = false): String {
        val attributes = entry.attributes
            .filter { includeOperational || schema == null || !schema.isOperational(it.name) }
            .sortedWith(compareBy<Attribute>({ !it.name.equals("objectClass", true) }, { schema?.isOperational(it.name) == true }, { it.name.lowercase() }))
            .map { attribute ->
                val values = attribute.valueByteArrays.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { String(it, Charsets.UTF_8) })
                if (ChangeRecords.isSensitive(attribute.name)) Attribute(attribute.name, values.map { digest(it) })
                else Attribute(attribute.name, *values.toTypedArray())
            }
        return Entry(entry.dn, attributes).toLDIF(0).joinToString("\n") + "\n"
    }

    private fun digest(value: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(value).take(6).joinToString("") { "%02x".format(it) }
        return "${ChangeRecords.MASK} (sha256 $hash...)"
    }
}

enum class BulkOperation(private val label: String, val needsValues: Boolean) {
    ADD_VALUES("Add values", true),
    REPLACE_VALUES("Replace all values with", true),
    DELETE_VALUES("Delete values", true),
    DELETE_ATTRIBUTE("Delete the attribute", false);

    override fun toString() = label
}

/** Builds the LDIF modify script applying one modification to many entries. */
object BulkModify {
    fun modification(operation: BulkOperation, attribute: String, values: List<String>): Modification = when (operation) {
        BulkOperation.ADD_VALUES -> Modification(ModificationType.ADD, attribute, *values.toTypedArray())
        BulkOperation.REPLACE_VALUES -> Modification(ModificationType.REPLACE, attribute, *values.toTypedArray())
        BulkOperation.DELETE_VALUES -> Modification(ModificationType.DELETE, attribute, *values.toTypedArray())
        BulkOperation.DELETE_ATTRIBUTE -> Modification(ModificationType.DELETE, attribute)
    }

    fun script(dns: List<String>, operation: BulkOperation, attribute: String, values: List<String>, header: List<String> = emptyList()): String {
        val modification = modification(operation, attribute, values)
        return buildString {
            header.forEach { append("# ").append(it).append('\n') }
            append("version: 1\n")
            for (dn in dns) {
                append('\n')
                LDIFModifyChangeRecord(dn, modification).toLDIF(0).forEach { append(it).append('\n') }
            }
        }
    }
}
