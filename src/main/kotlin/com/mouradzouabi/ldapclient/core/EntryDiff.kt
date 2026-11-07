package com.mouradzouabi.ldapclient.core

import com.unboundid.ldap.sdk.Modification
import com.unboundid.ldap.sdk.ModificationType
import java.nio.ByteBuffer
import java.util.TreeMap

/**
 * Computes the modifications needed to turn [original] into [modified]
 * (attribute names are compared case-insensitively, values byte-wise).
 *
 * Multi-valued attributes get value-level ADD/DELETE (cheap for big groups); attributes for which
 * [useReplace] returns true (passwords, single-valued) get a REPLACE.
 */
object EntryDiff {
    fun compute(
        original: Map<String, List<ByteArray>>,
        modified: Map<String, List<ByteArray>>,
        useReplace: (String) -> Boolean = { false },
    ): List<Modification> {
        val o = TreeMap<String, List<ByteArray>>(String.CASE_INSENSITIVE_ORDER).apply { putAll(original) }
        val m = TreeMap<String, List<ByteArray>>(String.CASE_INSENSITIVE_ORDER).apply { putAll(modified) }
        val names = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        o.keys.forEach { names[it] = it }
        m.keys.forEach { names[it] = it }

        val deletes = mutableListOf<Modification>()
        val adds = mutableListOf<Modification>()
        for (name in names.values) {
            val before = o[name].orEmpty()
            val after = m[name].orEmpty()
            val beforeSet = before.map(ByteBuffer::wrap).toSet()
            val afterSet = after.map(ByteBuffer::wrap).toSet()
            if (beforeSet == afterSet) continue
            when {
                after.isEmpty() -> deletes += Modification(ModificationType.DELETE, name)
                before.isEmpty() -> adds += Modification(ModificationType.ADD, name, *after.toTypedArray())
                useReplace(name) -> adds += Modification(ModificationType.REPLACE, name, *after.toTypedArray())
                else -> {
                    val removed = before.filter { ByteBuffer.wrap(it) !in afterSet }
                    val added = after.filter { ByteBuffer.wrap(it) !in beforeSet }
                    if (removed.isNotEmpty()) deletes += Modification(ModificationType.DELETE, name, *removed.toTypedArray())
                    if (added.isNotEmpty()) adds += Modification(ModificationType.ADD, name, *added.toTypedArray())
                }
            }
        }
        return deletes + adds
    }
}
