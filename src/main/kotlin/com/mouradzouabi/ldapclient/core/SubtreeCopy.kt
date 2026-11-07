package com.mouradzouabi.ldapclient.core

import com.unboundid.ldap.sdk.Attribute
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.Entry
import com.unboundid.ldap.sdk.Filter
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.Modification
import com.unboundid.ldap.sdk.ModificationType
import com.unboundid.ldap.sdk.ResultCode
import com.unboundid.ldap.sdk.SearchResultEntry
import com.unboundid.ldap.sdk.SearchScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class ConflictPolicy(private val label: String) {
    SKIP("Skip existing entries"),
    UPDATE("Overwrite attributes of existing entries"),
    FAIL("Report existing entries as errors");

    override fun toString() = label
}

data class CopyFailure(val dn: String, val message: String)

data class CopyResult(val copied: Int, val updated: Int, val skipped: Int, val failures: List<CopyFailure>, val stopped: Boolean) {
    val succeeded: Boolean get() = failures.isEmpty() && !stopped
}

/**
 * Copies [sourceDn] (and its subtree when [recursive]) of [source] to [targetDn] on [target], which may be another server.
 * The tree is walked level by level with one-level paged searches, so parents are always created before their children
 * and only one level of one parent is held in memory. Operational attributes are left out.
 */
class SubtreeCopy(
    private val source: LdapSession,
    private val target: LdapSession,
    private val sourceDn: String,
    private val targetDn: String,
    private val recursive: Boolean,
    private val conflict: ConflictPolicy,
    private val stopOnError: Boolean,
) {
    private var copied = 0
    private var updated = 0
    private var skipped = 0
    private val failures = mutableListOf<CopyFailure>()
    private lateinit var schema: LdapSchema

    init {
        if (source === target || source.config.id == target.config.id) {
            require(!LdapDnMove.isSameOrBelow(targetDn, sourceDn)) { "Cannot copy an entry into itself or its own subtree" }
        }
    }

    /** [onProgress] receives the number of processed entries and the DN being copied. */
    suspend fun run(onProgress: (Int, String) -> Unit = { _, _ -> }): CopyResult {
        schema = source.schemaAsync()
        val root = source.getEntry(sourceDn, listOf("*"))
            ?: throw LDAPException(ResultCode.NO_SUCH_OBJECT, "Entry $sourceDn not found")
        val stopped = try {
            onProgress(0, sourceDn)
            if (copy(root, targetDn, isRoot = true) && recursive) copyChildren(onProgress)
            false
        } catch (_: StopCopy) {
            true
        }
        return CopyResult(copied, updated, skipped, failures.toList(), stopped)
    }

    private suspend fun copyChildren(onProgress: (Int, String) -> Unit) {
        val queue = ArrayDeque<String>().apply { add(sourceDn) }
        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            val children = mutableListOf<SearchResultEntry>()
            source.search(SearchSpec(parent, SearchScope.ONE, Filter.createPresenceFilter("objectClass"), CHILD_ATTRIBUTES), 0) {
                synchronized(children) { children += it }
            }
            for (child in children) {
                currentCoroutineContext().ensureActive()
                onProgress(copied + updated + skipped + failures.size, child.dn)
                if (copy(child, LdapDnMove.rebase(child.dn, sourceDn, targetDn), isRoot = false) && mayHaveChildren(child)) {
                    queue += child.dn
                }
            }
        }
    }

    /** Returns true when the entry exists on the target afterwards, i.e. its children can be copied. */
    private suspend fun copy(entry: Entry, newDn: String, isRoot: Boolean): Boolean {
        var attributes = entry.attributes.filter { !schema.isOperational(it.name) }
        if (isRoot) attributes = renameRdnValues(attributes, entry.dn, newDn)
        try {
            target.add(Entry(newDn, attributes))
            copied++
            return true
        } catch (e: LDAPException) {
            if (e.resultCode == ResultCode.ENTRY_ALREADY_EXISTS && conflict != ConflictPolicy.FAIL) {
                if (conflict == ConflictPolicy.SKIP) {
                    skipped++
                    return true
                }
                return try {
                    target.modify(newDn, attributes.map { Modification(ModificationType.REPLACE, it.name, *it.valueByteArrays) })
                    updated++
                    true
                } catch (m: LDAPException) {
                    fail(newDn, m)
                }
            }
            return fail(newDn, e)
        }
    }

    private fun fail(dn: String, e: LDAPException): Boolean {
        failures += CopyFailure(dn, LdapErrors.describe(e))
        if (stopOnError) throw StopCopy()
        return false
    }

    /** When the root gets a new RDN, its old naming values are replaced by the new ones. */
    private fun renameRdnValues(attributes: List<Attribute>, oldDn: String, newDn: String): List<Attribute> {
        val oldRdn = DN(oldDn).rdn ?: return attributes
        val newRdn = DN(newDn).rdn ?: return attributes
        if (oldRdn == newRdn) return attributes
        val entry = Entry(newDn, attributes)
        oldRdn.attributeNames.zip(oldRdn.attributeValues).forEach { (name, value) -> entry.removeAttributeValue(name, value) }
        newRdn.attributeNames.zip(newRdn.attributeValues).forEach { (name, value) -> entry.addAttribute(name, value) }
        return entry.attributes.toList()
    }

    private fun mayHaveChildren(entry: SearchResultEntry): Boolean {
        entry.getAttributeValue("hasSubordinates")?.let { return !it.equals("FALSE", ignoreCase = true) }
        entry.getAttributeValueAsInteger("numSubordinates")?.let { return it > 0 }
        return true
    }

    private class StopCopy : RuntimeException(null, null, false, false)

    companion object {
        private val CHILD_ATTRIBUTES = listOf("*", "hasSubordinates", "numSubordinates")
    }
}
