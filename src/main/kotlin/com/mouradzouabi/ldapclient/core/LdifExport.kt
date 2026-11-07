package com.mouradzouabi.ldapclient.core

import com.unboundid.ldap.sdk.Entry
import com.unboundid.ldap.sdk.Filter
import com.unboundid.ldap.sdk.SearchScope
import com.unboundid.ldif.LDIFWriter
import java.io.IOException
import java.io.OutputStream

enum class ExportScope(val label: String, val searchScope: SearchScope) {
    SUBTREE("Entry and its whole subtree", SearchScope.SUB),
    CHILDREN("Direct children only", SearchScope.ONE),
    ENTRY("This entry only", SearchScope.BASE),
    ;

    override fun toString() = label
}

data class LdifExportOptions(
    val scope: ExportScope = ExportScope.SUBTREE,
    val filter: Filter = Filter.createPresenceFilter("objectClass"),
    val attributes: List<String> = listOf("*"),
    val includeOperational: Boolean = false,
    /** 0 disables line wrapping. */
    val wrapColumn: Int = 76,
    val header: String? = null,
)

data class LdifExportSummary(val entries: Int)

/**
 * Streams a search into an LDIF file. For full subtree exports entries are written parents-first
 * (entries arriving before their parent are held back until it is written), so the file can be
 * re-imported as is. Memory use stays small when the server already returns hierarchical order.
 */
object LdifExport {

    suspend fun export(
        session: LdapSession,
        baseDn: String,
        options: LdifExportOptions,
        out: OutputStream,
        onProgress: (Int) -> Unit = {},
    ): LdifExportSummary {
        val attributes = buildList {
            addAll(options.attributes.ifEmpty { listOf("*") })
            if (options.includeOperational && "+" !in this) add("+")
        }
        // Some servers return operational attributes for "*"; keep them out unless included or listed explicitly.
        val schema = if (options.includeOperational || "+" in attributes) null else session.schemaAsync()
        val requested = attributes.mapTo(HashSet()) { LdapSchema.baseName(it).lowercase() }
        fun userAttributesOnly(entry: Entry): Entry {
            if (schema == null) return entry
            val kept = entry.attributes.filter { !schema.isOperational(it.name) || LdapSchema.baseName(it.name).lowercase() in requested }
            return if (kept.size == entry.attributes.size) entry else Entry(entry.dn, kept)
        }
        val writer = LDIFWriter(out).apply { setWrapColumn(options.wrapColumn) }
        val orderer = if (options.scope == ExportScope.SUBTREE && isMatchAll(options.filter)) ParentFirstOrderer(baseDn) else null
        val lock = Any()
        var written = 0
        var failure: IOException? = null

        fun write(entry: Entry) {
            writer.writeEntry(entry)
            written++
            if (written % 100 == 0) onProgress(written)
        }

        try {
            options.header?.let { writer.writeComment(it, false, true) }
            val spec = SearchSpec(baseDn, options.scope.searchScope, options.filter, attributes)
            session.search(spec, 0) { result ->
                val entry = userAttributesOnly(result)
                synchronized(lock) {
                    if (failure != null) return@synchronized
                    try {
                        if (orderer == null) write(entry) else orderer.accept(entry, ::write)
                    } catch (e: IOException) {
                        failure = e
                    }
                }
            }
            synchronized(lock) {
                failure?.let { throw it }
                orderer?.drain(::write)
                writer.flush()
            }
        } finally {
            onProgress(written)
        }
        return LdifExportSummary(written)
    }

    private fun isMatchAll(filter: Filter): Boolean =
        filter.filterType == Filter.FILTER_TYPE_PRESENCE && filter.attributeName.equals("objectClass", ignoreCase = true)

    /** Holds back entries whose parent has not been written yet. Written DNs are tracked as 64-bit hashes. */
    internal class ParentFirstOrderer(baseDn: String) {
        private val base = normalize(baseDn)
        private val written = LongHashSet()
        private val pending = HashMap<String, MutableList<Entry>>()

        fun accept(entry: Entry, write: (Entry) -> Unit) {
            val dn = normalize(entry.dn)
            val parent = LdapDn.parse(entry.dn)?.parent?.toNormalizedString()
            if (dn == base || parent == null || hash(parent) in written) {
                emit(entry, dn, write)
            } else {
                pending.getOrPut(parent) { ArrayList() } += entry
            }
        }

        private fun emit(entry: Entry, dn: String, write: (Entry) -> Unit) {
            val stack = ArrayDeque<Pair<Entry, String>>()
            stack += entry to dn
            while (stack.isNotEmpty()) {
                val (e, key) = stack.removeLast()
                write(e)
                written += hash(key)
                pending.remove(key)?.forEach { child -> stack += child to normalize(child.dn) }
            }
        }

        /** Entries whose parent never arrived (e.g. not readable); written shallowest first. */
        fun drain(write: (Entry) -> Unit) {
            val rest = pending.values.flatten().sortedBy { LdapDn.depth(it.dn) }
            pending.clear()
            rest.forEach(write)
        }

        private fun normalize(dn: String) = LdapDn.parse(dn)?.toNormalizedString() ?: dn.lowercase()

        private fun hash(s: String): Long {
            var h = -0x340d631b7bdddcdbL
            for (c in s) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }
    }

    /** Minimal open-addressing set of longs (avoids boxing for very large exports). */
    private class LongHashSet {
        private var keys = LongArray(1024)
        private var used = BooleanArray(1024)
        private var size = 0

        operator fun contains(key: Long): Boolean {
            var i = index(key, keys.size)
            while (used[i]) {
                if (keys[i] == key) return true
                i = (i + 1) and (keys.size - 1)
            }
            return false
        }

        operator fun plusAssign(key: Long) {
            if ((size + 1) * 2 > keys.size) grow()
            if (insert(keys, used, key)) size++
        }

        private fun insert(k: LongArray, u: BooleanArray, key: Long): Boolean {
            var i = index(key, k.size)
            while (u[i]) {
                if (k[i] == key) return false
                i = (i + 1) and (k.size - 1)
            }
            k[i] = key
            u[i] = true
            return true
        }

        private fun grow() {
            val k = LongArray(keys.size * 2)
            val u = BooleanArray(keys.size * 2)
            for (i in keys.indices) if (used[i]) insert(k, u, keys[i])
            keys = k
            used = u
        }

        private fun index(key: Long, capacity: Int): Int {
            val mixed = key xor (key ushr 32) xor (key ushr 17)
            return mixed.toInt() and (capacity - 1)
        }
    }
}
