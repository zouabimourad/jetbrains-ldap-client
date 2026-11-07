package com.mouradzouabi.ldapclient.ui.membership

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import com.mouradzouabi.ldapclient.core.GroupMembership
import com.mouradzouabi.ldapclient.core.LdapDn
import com.mouradzouabi.ldapclient.core.LdapErrors
import com.mouradzouabi.ldapclient.service.LdapConnectionManager
import com.mouradzouabi.ldapclient.service.LdapNotifications
import com.mouradzouabi.ldapclient.service.LdapProjectService
import com.mouradzouabi.ldapclient.ui.EntryOperations
import com.mouradzouabi.ldapclient.ui.UiDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel
import kotlin.coroutines.cancellation.CancellationException

/**
 * "Show Groups" / "Show Members" navigation. Lookups run in the background; results are shown in a
 * speed-search popup next to the anchor component and chosen items are opened in the entry editor.
 */
object MembershipNavigator {
    private const val MAX_OPEN_AT_ONCE = 20

    private class Item(val dn: String?, val uid: String?, val primary: String, val secondary: String?, val icon: Icon) {
        val filterText: String get() = listOfNotNull(primary, dn, secondary).joinToString(" ")
    }

    private class Popup(val title: String, val ad: String, val empty: String, val items: List<Item>)

    fun showGroups(project: Project, configId: String, dn: String, anchor: JComponent?) {
        val name = GroupMembership.rdnValue(dn)
        load(project, "Finding groups of $name", anchor, open = { EntryOperations.open(project, configId, it.dn!!) }) {
            val session = LdapConnectionManager.getInstance().connect(configId)
            val result = GroupMembership.groupsOf(session, dn)
            val items = result.groups.map { g ->
                val parent = LdapDn.parent(g.dn).orEmpty()
                Item(g.dn, null, g.name, if (g.via != null) "via ${g.via}  ·  $parent" else parent, AllIcons.Nodes.Related)
            }
            val direct = result.groups.count { it.via == null }
            Popup(
                title = "Groups of $name",
                ad = "$direct direct, ${result.groups.size - direct} nested" + if (result.truncated) " (truncated)" else "",
                empty = "<b>${StringUtil.escapeXmlEntities(name)}</b> is not a member of any group",
                items = items,
            )
        }
    }

    fun showMembers(project: Project, configId: String, groupDn: String, anchor: JComponent?) {
        val name = GroupMembership.rdnValue(groupDn)
        val open: (Item) -> Unit = { item ->
            if (item.dn != null) EntryOperations.open(project, configId, item.dn) else openMemberUid(project, configId, item.uid!!)
        }
        load(project, "Loading members of $name", anchor, open) {
            val session = LdapConnectionManager.getInstance().connect(configId)
            val result = GroupMembership.membersOf(session, groupDn)
            val items = result.members.map { m ->
                if (m.uid != null) {
                    Item(null, m.uid, m.uid, "memberUid", AllIcons.General.User)
                } else {
                    val secondary = listOfNotNull(LdapDn.parent(m.dn!!), "(dynamic)".takeIf { m.source == "memberURL" }).joinToString("  ")
                    Item(m.dn, null, m.label, secondary, AllIcons.General.User)
                }
            }
            Popup(
                title = "Members of $name",
                ad = "${items.size} members" + if (result.truncated) " (truncated)" else "",
                empty = "<b>${StringUtil.escapeXmlEntities(name)}</b> has no members",
                items = items,
            )
        }
    }

    /** Opens the entry whose uid is [uid] (posixGroup memberUid values). */
    fun openMemberUid(project: Project, configId: String, uid: String) {
        LdapProjectService.getInstance(project).scope.launch {
            try {
                val session = LdapConnectionManager.getInstance().connect(configId)
                val dn = withBackgroundProgress(project, "Resolving uid=$uid") { GroupMembership.resolveUid(session, uid) }
                withContext(UiDispatcher) {
                    if (dn != null) EntryOperations.open(project, configId, dn)
                    else LdapNotifications.info(project, "Member not found", "No entry with uid=$uid")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LdapNotifications.error(project, "Cannot resolve uid=$uid", LdapErrors.describe(e))
            }
        }
    }

    private fun load(project: Project, progressTitle: String, anchor: JComponent?, open: (Item) -> Unit, compute: suspend () -> Popup) {
        LdapProjectService.getInstance(project).scope.launch {
            try {
                val popup = withBackgroundProgress(project, progressTitle, cancellable = true) { compute() }
                withContext(UiDispatcher) { show(project, popup, anchor, open) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LdapNotifications.error(project, "$progressTitle failed", LdapErrors.describe(e))
            }
        }
    }

    private fun show(project: Project, popup: Popup, anchor: JComponent?, open: (Item) -> Unit) {
        val factory = JBPopupFactory.getInstance()
        val location = anchor?.takeIf { it.isShowing }?.let { factory.guessBestPopupLocation(it) }
        if (popup.items.isEmpty()) {
            if (location == null) {
                LdapNotifications.info(project, popup.title, StringUtil.unescapeXmlEntities(popup.empty.replace(Regex("</?b>"), "")))
                return
            }
            factory.createHtmlTextBalloonBuilder(popup.empty, MessageType.INFO, null)
                .setFadeoutTime(4000)
                .createBalloon()
                .show(location, Balloon.Position.below)
            return
        }
        val chooser = factory.createPopupChooserBuilder(popup.items)
            .setTitle(popup.title)
            .setRenderer(ItemRenderer())
            .setNamerForFiltering { it.filterText }
            .setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
            .setItemsChosenCallback { chosen -> chosen.take(MAX_OPEN_AT_ONCE).forEach(open) }
            .setAdText("${popup.ad}  ·  Enter to open")
            .setMovable(true)
            .setResizable(true)
            .setDimensionServiceKey("LdapClient.MembershipPopup")
            .createPopup()
        if (location != null) chooser.show(location) else chooser.showCenteredInCurrentWindow(project)
    }

    private class ItemRenderer : ColoredListCellRenderer<Item>() {
        override fun customizeCellRenderer(list: JList<out Item>, value: Item, index: Int, selected: Boolean, hasFocus: Boolean) {
            icon = value.icon
            ipad = JBUI.insets(0, 4)
            append(value.primary)
            value.secondary?.takeIf { it.isNotEmpty() }?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            toolTipText = value.dn
        }
    }
}
