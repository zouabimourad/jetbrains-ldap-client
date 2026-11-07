package com.mouradzouabi.ldapclient.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.BadgeIcon
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.mouradzouabi.ldapclient.service.LdapConnectionManager
import javax.swing.Icon
import javax.swing.JTree

class LdapTreeCellRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
    ) {
        when (value) {
            is ConnectionNode -> {
                val manager = LdapConnectionManager.getInstance()
                val session = manager.session(value.configId)
                val connected = session != null
                icon = when {
                    manager.isConnecting(value.configId) -> AnimatedIcon.Default.INSTANCE
                    value.error != null -> AllIcons.General.Error
                    connected -> CONNECTED_ICON
                    else -> AllIcons.Nodes.DataSchema
                }
                toolTipText = when {
                    session != null -> "Connected: ${session.serverDescription}"
                    manager.isConnecting(value.configId) -> "Connecting…"
                    value.error != null -> value.error
                    else -> "Not connected"
                }
                append(value.config.displayName, if (connected) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  ${value.config.host}:${value.config.port}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (value.config.readOnly) append("  read-only", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            }
            is EntryNode -> {
                icon = iconFor(value.objectClasses, value.isBase)
                append(value.label, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                val count = value.loadedCount
                if (value.loadState == LoadState.LOADED && count > 0) {
                    val more = value.cursor?.exhausted == false || value.truncated
                    append("  ($count${if (more) "+" else ""})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                value.childFilter?.let { append("  [filter: $it]", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) }
            }
            is LoadMoreNode -> {
                icon = AllIcons.General.ArrowDown
                append("Load more…", SimpleTextAttributes.LINK_ATTRIBUTES)
                append("  (double-click or Enter)", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
            is StatusNode -> when (value.kind) {
                StatusNode.Kind.LOADING -> {
                    icon = AnimatedIcon.Default.INSTANCE
                    append(value.text, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                StatusNode.Kind.INFO -> {
                    icon = AllIcons.General.Information
                    append(value.text, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
                StatusNode.Kind.ERROR -> {
                    icon = AllIcons.General.Error
                    append(value.text, SimpleTextAttributes.ERROR_ATTRIBUTES)
                }
            }
        }
    }

    companion object {
        val CONNECTED_COLOR: JBColor = JBColor.namedColor("Label.successForeground", JBColor(0x369650, 0x5FB865))
        private val CONNECTED_ICON = BadgeIcon(AllIcons.Nodes.DataSchema, CONNECTED_COLOR)

        fun iconFor(objectClasses: List<String>, isBase: Boolean): Icon {
            val oc = objectClasses.map { it.lowercase() }.toSet()
            return when {
                oc.any { it in PERSON } -> AllIcons.General.User
                oc.any { it in GROUP } -> AllIcons.Nodes.Related
                oc.any { it in CONTAINER } -> AllIcons.Nodes.Folder
                oc.any { it in DOMAIN } || isBase -> AllIcons.Nodes.Module
                oc.any { it.contains("olc") || it == "subentry" || it == "subschema" } -> AllIcons.General.Settings
                oc.any { it in DEVICE } -> AllIcons.Nodes.Desktop
                oc.any { it == "alias" || it == "referral" } -> AllIcons.Nodes.Symlink
                oc.isEmpty() -> AllIcons.Nodes.Folder
                else -> AllIcons.Nodes.Tag
            }
        }

        private val PERSON = setOf("person", "inetorgperson", "organizationalperson", "user", "posixaccount", "account", "shadowaccount")
        private val GROUP = com.mouradzouabi.ldapclient.core.GroupMembership.GROUP_CLASSES
        private val CONTAINER = setOf("organizationalunit", "container", "builtindomain", "nscontainer")
        private val DOMAIN = setOf("domain", "dcobject", "organization", "domaindns", "country", "locality")
        private val DEVICE = setOf("device", "computer", "iphost", "ieee802device")
    }
}
