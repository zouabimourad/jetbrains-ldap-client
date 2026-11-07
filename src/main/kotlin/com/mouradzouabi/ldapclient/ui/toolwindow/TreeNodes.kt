package com.mouradzouabi.ldapclient.ui.toolwindow

import com.mouradzouabi.ldapclient.core.LdapDn
import com.mouradzouabi.ldapclient.core.PagedSearch
import com.mouradzouabi.ldapclient.model.LdapConnectionConfig
import javax.swing.tree.DefaultMutableTreeNode

abstract class LdapTreeNode : DefaultMutableTreeNode()

class RootNode : LdapTreeNode()

class ConnectionNode(var config: LdapConnectionConfig) : LdapTreeNode() {
    val configId: String get() = config.id
    var populated = false
    var error: String? = null
}

enum class LoadState { NOT_LOADED, LOADING, LOADED }

class EntryNode(
    val configId: String,
    val dn: String,
    val isBase: Boolean,
    var objectClasses: List<String> = emptyList(),
    mayHaveChildren: Boolean = true,
) : LdapTreeNode() {
    val label: String = if (isBase) dn.ifEmpty { "Root DSE" } else LdapDn.rdn(dn)
    var loadState = LoadState.NOT_LOADED
    var cursor: PagedSearch? = null
    var childFilter: String? = null
    var truncated = false
    var loadedCount = 0
    /** Incremented on every reset so that results of stale loads are discarded. */
    var generation = 0

    init {
        if (mayHaveChildren) add(StatusNode("Loading…", StatusNode.Kind.LOADING))
    }

    val connectionNode: ConnectionNode?
        get() = path.firstNotNullOfOrNull { it as? ConnectionNode }
}

class LoadMoreNode(val owner: EntryNode) : LdapTreeNode()

class StatusNode(val text: String, val kind: Kind) : LdapTreeNode() {
    enum class Kind { LOADING, INFO, ERROR }
}
