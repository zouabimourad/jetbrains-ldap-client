package com.mouradzouabi.ldapclient.model

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.util.messages.Topic

/**
 * Application wide events. Always delivered on the EDT (see [LdapEvents]).
 */
interface LdapListener {
    fun connectionsChanged() {}
    fun connectionStateChanged(configId: String) {}
    fun operationalAttributesToggled(show: Boolean) {}

    /** Children of [parentDn] changed (entry added, deleted or renamed). */
    fun childrenChanged(configId: String, parentDn: String) {}

    /** [dn] was deleted (or renamed away). */
    fun entryDeleted(configId: String, dn: String) {}

    /** Attributes of [dn] were modified. */
    fun entryModified(configId: String, dn: String) {}

    /**
     * Many entries changed at once (e.g. after running an LDIF script). DNs are normalized
     * (see [BulkChanges.normalizeDn]). Default implementation forwards to the per-entry callbacks.
     */
    fun entriesChanged(configId: String, changes: BulkChanges) {
        changes.deleted.forEach { entryDeleted(configId, it) }
        changes.modified.forEach { entryModified(configId, it) }
        changes.parents.forEach { childrenChanged(configId, it) }
    }

    companion object {
        @JvmField
        @Topic.AppLevel
        val TOPIC: Topic<LdapListener> = Topic.create("LDAP Client events", LdapListener::class.java)
    }
}

/** Sets of normalized DNs affected by a bulk operation. */
class BulkChanges {
    val parents: MutableSet<String> = LinkedHashSet()
    val deleted: MutableSet<String> = LinkedHashSet()
    val modified: MutableSet<String> = LinkedHashSet()

    val isEmpty: Boolean get() = parents.isEmpty() && deleted.isEmpty() && modified.isEmpty()

    fun added(dn: String) = parent(dn)?.let { parents += it }
    fun modified(dn: String) = normalizeDn(dn).let { modified += it }
    fun deleted(dn: String) {
        deleted += normalizeDn(dn)
        parent(dn)?.let { parents += it }
    }

    private fun parent(dn: String): String? = com.mouradzouabi.ldapclient.core.LdapDn.parse(dn)?.parent?.toNormalizedString()

    companion object {
        fun normalizeDn(dn: String): String = com.mouradzouabi.ldapclient.core.LdapDn.parse(dn)?.toNormalizedString() ?: dn.lowercase()
    }
}

object LdapEvents {
    fun fire(block: (LdapListener) -> Unit) {
        val app = ApplicationManager.getApplication()
        app.invokeLater({ block(app.messageBus.syncPublisher(LdapListener.TOPIC)) }, ModalityState.any())
    }
}
