package com.mouradzouabi.ldapclient.core

import com.unboundid.ldap.sdk.Attribute
import com.unboundid.ldap.sdk.Entry
import com.unboundid.ldap.sdk.Modification
import com.unboundid.ldif.LDIFAddChangeRecord
import com.unboundid.ldif.LDIFChangeRecord
import com.unboundid.ldif.LDIFModifyChangeRecord
import java.time.Instant

/** A write operation sent to a directory server, with its outcome ([error] is null on success). */
data class LdapOperation(
    val time: Instant,
    val connectionId: String,
    val connectionName: String,
    val type: String,
    val dn: String,
    /** LDIF change record with password values masked. */
    val ldif: String,
    val error: String?,
    val durationMillis: Long,
) {
    val succeeded: Boolean get() = error == null
}

fun interface LdapOperationListener {
    fun operationCompleted(operation: LdapOperation)
}

/** Renders change records as LDIF while hiding secrets, for previews and the operation log. */
object ChangeRecords {
    const val MASK = "********"
    private const val MAX_LENGTH = 64 * 1024

    private val SENSITIVE = setOf("userpassword", "unicodepwd", "clearpassword", "authpassword", "sambantpassword", "sambalmpassword")

    fun isSensitive(attribute: String) = LdapSchema.baseName(attribute).lowercase() in SENSITIVE

    fun modify(dn: String, modifications: List<Modification>): String =
        toLdif(LDIFModifyChangeRecord(dn, *modifications.toTypedArray()))

    fun toLdif(record: LDIFChangeRecord): String {
        val text = mask(record).toLDIFString(0)
        return if (text.length > MAX_LENGTH) text.take(MAX_LENGTH) + "\n# … truncated\n" else text
    }

    fun mask(record: LDIFChangeRecord): LDIFChangeRecord = when (record) {
        is LDIFAddChangeRecord -> if (record.attributes.none { isSensitive(it.name) }) record else {
            LDIFAddChangeRecord(maskEntry(record.entryToAdd), record.controls)
        }
        is LDIFModifyChangeRecord -> if (record.modifications.none { isSensitive(it.attributeName) }) record else {
            val masked = record.modifications.map {
                if (!isSensitive(it.attributeName) || !it.hasValue()) it
                else Modification(it.modificationType, it.attributeName, *Array(it.values.size) { MASK })
            }
            LDIFModifyChangeRecord(record.dn, masked, record.controls)
        }
        else -> record
    }

    fun maskEntry(entry: Entry): Entry = Entry(
        entry.dn,
        entry.attributes.map { if (isSensitive(it.name)) Attribute(it.name, *Array(it.size()) { MASK }) else it },
    )
}
