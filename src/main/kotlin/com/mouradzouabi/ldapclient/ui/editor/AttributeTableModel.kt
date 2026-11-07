package com.mouradzouabi.ldapclient.ui.editor

import com.unboundid.ldap.sdk.Attribute
import com.unboundid.ldap.sdk.Entry
import com.unboundid.ldap.sdk.Modification
import com.mouradzouabi.ldapclient.core.AttributeKind
import com.mouradzouabi.ldapclient.core.AttributeKinds
import com.mouradzouabi.ldapclient.core.EntryDiff
import com.mouradzouabi.ldapclient.core.LdapDn
import com.mouradzouabi.ldapclient.core.LdapSchema
import java.nio.ByteBuffer
import java.util.TreeMap
import javax.swing.table.AbstractTableModel

class AttributeRow(
    val attribute: String,
    val value: ByteArray,
    val index: Int,
    val first: Boolean,
    val count: Int,
    /** Position of the attribute among displayed attributes (used for group shading and separators). */
    val group: Int = 0,
) {
    val last: Boolean get() = index == count - 1
}

/**
 * Working copy of an entry: one table row per attribute value. Edits are kept locally until saved;
 * [modifications] computes the minimal LDAP modify request.
 */
class AttributeTableModel : AbstractTableModel() {
    var schema = LdapSchema(null)
        private set
    var dn: String = ""
        private set
    var readOnly = false
    var showOperational = false
        set(value) {
            field = value
            rebuild()
        }
    var onChange: () -> Unit = {}

    private val original = TreeMap<String, List<ByteArray>>(String.CASE_INSENSITIVE_ORDER)
    private val working = TreeMap<String, MutableList<ByteArray>>(String.CASE_INSENSITIVE_ORDER)
    private val kinds = HashMap<String, AttributeKind>()
    private var rdnPairs: List<Pair<String, String>> = emptyList()
    private var mustAttributes: Set<String> = emptySet()
    private var allowedOids: Set<String>? = null

    var rows: List<AttributeRow> = emptyList()
        private set

    val loaded: Boolean get() = dn.isNotEmpty() || original.isNotEmpty()

    val objectClasses: List<String> get() = working["objectClass"].orEmpty().map { String(it, Charsets.UTF_8) }

    fun setEntry(entry: Entry, schema: LdapSchema) {
        this.schema = schema
        dn = entry.dn
        rdnPairs = LdapDn.rdnPairs(dn)
        original.clear()
        working.clear()
        kinds.clear()
        for (attribute in entry.attributes) {
            val values = attribute.valueByteArrays.toList()
            original[attribute.name] = values
            working[attribute.name] = values.toMutableList()
        }
        rebuild()
    }

    fun clear() {
        dn = ""
        original.clear()
        working.clear()
        rebuild()
    }

    private fun rebuild() {
        val objectClasses = working["objectClass"].orEmpty().map { String(it, Charsets.UTF_8) }
        mustAttributes = schema.requiredAttributes(objectClasses).mapTo(sortedSetOf(String.CASE_INSENSITIVE_ORDER)) { it }
        allowedOids = schema.allowedAttributeOids(objectClasses)
        val names = working.keys.filter { showOperational || !isOperational(it) }
            .sortedWith(compareBy<String>({ !it.equals("objectClass", true) }, { isOperational(it) }, { it.lowercase() }))
        rows = names.flatMapIndexed { group, name ->
            val values = working[name].orEmpty()
            values.mapIndexed { i, v -> AttributeRow(name, v, i, i == 0, values.size, group) }
        }
        fireTableDataChanged()
        onChange()
    }

    fun kind(attribute: String): AttributeKind = kinds.getOrPut(attribute.lowercase()) {
        AttributeKinds.classify(attribute, schema, (original[attribute] ?: working[attribute]).orEmpty())
    }

    fun isOperational(attribute: String) = schema.isOperational(attribute)

    fun isMust(attribute: String) = attribute in mustAttributes

    /** False when the schema does not define [attribute] or the object classes do not allow it. */
    fun isAllowed(attribute: String) = schema.isAllowed(attribute, allowedOids)

    fun isRdnValue(row: AttributeRow): Boolean {
        val text = String(row.value, Charsets.UTF_8)
        return rdnPairs.any { (name, value) -> name.equals(row.attribute, true) && value.equals(text, true) }
    }

    fun isAttributeModified(attribute: String): Boolean = !sameValues(original[attribute], working[attribute])

    fun isValueAdded(row: AttributeRow): Boolean = original[row.attribute]?.none { it.contentEquals(row.value) } ?: true

    val isModified: Boolean
        get() = (original.keys + working.keys).any { isAttributeModified(it) }

    fun canEdit(row: AttributeRow) = !readOnly && !isOperational(row.attribute) && !isRdnValue(row)

    fun objectClasses(): List<String> = working["objectClass"].orEmpty().map { String(it, Charsets.UTF_8) }

    fun attributeNames(): Set<String> = working.keys

    fun setValue(row: AttributeRow, bytes: ByteArray) {
        val list = working[row.attribute] ?: return
        if (row.index !in list.indices || list[row.index].contentEquals(bytes)) return
        if (list.any { it.contentEquals(bytes) }) list.removeAt(row.index) else list[row.index] = bytes
        if (row.attribute.equals("objectClass", true)) kinds.clear()
        rebuild()
    }

    fun addValue(attribute: String, bytes: ByteArray) {
        val list = working.getOrPut(attribute) { mutableListOf() }
        if (list.none { it.contentEquals(bytes) }) list += bytes
        rebuild()
    }

    fun removeValue(row: AttributeRow) {
        val list = working[row.attribute] ?: return
        if (row.index in list.indices) list.removeAt(row.index)
        if (list.isEmpty()) working.remove(row.attribute)
        rebuild()
    }

    fun revert() {
        working.clear()
        original.forEach { (k, v) -> working[k] = v.toMutableList() }
        rebuild()
    }

    fun modifications(): List<Modification> {
        val editable = { name: String -> !isOperational(name) }
        return EntryDiff.compute(
            original.filterKeys(editable),
            working.filterKeys(editable),
            useReplace = { kind(it) == AttributeKind.PASSWORD || schema.isSingleValued(it) },
        )
    }

    fun currentEntry(includeOperational: Boolean): Entry =
        Entry(dn, working.filterKeys { includeOperational || !isOperational(it) }.map { (name, values) -> Attribute(name, *values.toTypedArray()) })

    override fun getRowCount() = rows.size
    override fun getColumnCount() = 2
    override fun getColumnName(column: Int) = if (column == 0) "Attribute" else "Value"
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = rows[rowIndex]
        return if (columnIndex == 0) row.attribute else String(row.value, Charsets.UTF_8)
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
        if (columnIndex != 1) return false
        val row = rows[rowIndex]
        return canEdit(row) && kind(row.attribute).inlineEditable && String(row.value, Charsets.UTF_8).none { it == '\n' }
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        val text = aValue as? String ?: return
        val row = rows.getOrNull(rowIndex) ?: return
        if (text.isEmpty()) removeValue(row) else setValue(row, text.toByteArray(Charsets.UTF_8))
    }

    private fun sameValues(a: List<ByteArray>?, b: List<ByteArray>?): Boolean =
        a.orEmpty().map(ByteBuffer::wrap).toSet() == b.orEmpty().map(ByteBuffer::wrap).toSet()
}
