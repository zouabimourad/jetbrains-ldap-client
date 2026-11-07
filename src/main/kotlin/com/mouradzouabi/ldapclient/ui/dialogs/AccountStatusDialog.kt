package com.mouradzouabi.ldapclient.ui.dialogs

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mouradzouabi.ldapclient.core.AccountFact
import com.mouradzouabi.ldapclient.core.AccountFlavor
import com.mouradzouabi.ldapclient.core.AccountStatus
import com.mouradzouabi.ldapclient.core.AccountTools
import com.mouradzouabi.ldapclient.core.ChangeRecords
import com.mouradzouabi.ldapclient.core.LdapErrors
import com.mouradzouabi.ldapclient.core.LdapSession
import com.mouradzouabi.ldapclient.model.LdapEvents
import com.mouradzouabi.ldapclient.service.LdapConnectionManager
import com.mouradzouabi.ldapclient.service.LdapProjectService
import com.mouradzouabi.ldapclient.ui.TableGrid
import com.mouradzouabi.ldapclient.ui.UiDispatcher
import com.unboundid.ldap.sdk.Control
import com.unboundid.ldap.sdk.Entry
import com.unboundid.ldap.sdk.Modification
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import kotlin.coroutines.cancellation.CancellationException

/**
 * Shows whether an account is disabled, locked or has an expired password (Active Directory, OpenLDAP ppolicy and
 * 389 DS) and offers the usual help-desk actions. Every change is previewed as LDIF before it is sent.
 */
class AccountStatusDialog(private val project: Project, private val configId: String, private val dn: String) : DialogWrapper(project, true) {

    private val scope = LdapProjectService.getInstance(project).childScope(disposable)
    private val summary = JBLabel("Loading…", AllIcons.Process.Step_1, JBLabel.LEADING).apply {
        font = JBUI.Fonts.label().asBold()
    }
    private val flavorLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val facts = FactsModel()
    private val table = JBTable(facts).apply {
        TableGrid.apply(this)
        setDefaultRenderer(Any::class.java, FactRenderer())
        columnModel.getColumn(0).preferredWidth = JBUI.scale(200)
        columnModel.getColumn(1).preferredWidth = JBUI.scale(380)
    }

    private val unlockButton = JButton("Unlock").apply { addActionListener { change("Unlock Account") { e, f -> AccountTools.unlock(e, f) } } }
    private val disableButton = JButton("Disable").apply {
        addActionListener {
            val disable = !(status?.disabled ?: false)
            change(if (disable) "Disable Account" else "Enable Account") { e, f -> AccountTools.setDisabled(e, f, disable) }
        }
    }
    private val forceButton = JButton("Require Password Change").apply {
        addActionListener { change("Require Password Change") { _, f -> AccountTools.forcePasswordChange(f) } }
    }

    private var session: LdapSession? = null
    private var entry: Entry? = null
    private var flavor = AccountFlavor.UNKNOWN
    private var status: AccountStatus? = null

    init {
        title = "Account Status"
        setCancelButtonText("Close")
        updateButtons()
        init()
        reload()
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun createLeftSideActions(): Array<Action> = arrayOf(object : AbstractAction("Refresh") {
        override fun actionPerformed(e: ActionEvent) = reload()
    })

    override fun createCenterPanel(): JComponent = panel {
        row { cell(com.intellij.ui.components.JBLabel(dn).setCopyable(true)) }
        row { cell(summary) }
        row { cell(flavorLabel) }
        row { cell(JBScrollPane(table)).align(Align.FILL) }.resizableRow()
        row {
            cell(unlockButton)
            cell(disableButton)
            cell(forceButton)
        }
    }.apply { preferredSize = JBUI.size(640, 420) }

    override fun getDimensionServiceKey() = "LdapClient.AccountStatus"

    private fun reload() {
        summary.text = "Loading…"
        summary.icon = AllIcons.Process.Step_1
        scope.launch {
            try {
                val session = LdapConnectionManager.getInstance().connect(configId)
                val entry = session.getEntry(dn, AccountTools.ATTRIBUTES) ?: error("Entry $dn not found")
                val flavor = AccountTools.flavor(entry, session.rootDse?.vendorName)
                val policyDn = AccountTools.policyDn(entry, flavor, session.rootDse?.getAttributeValue("defaultNamingContext"))
                val policy = policyDn?.let { runCatching { session.getEntry(it, AccountTools.POLICY_ATTRIBUTES) }.getOrNull() }
                val status = AccountTools.analyze(entry, policy, flavor)
                withContext(UiDispatcher) {
                    this@AccountStatusDialog.session = session
                    this@AccountStatusDialog.entry = entry
                    this@AccountStatusDialog.flavor = flavor
                    this@AccountStatusDialog.status = status
                    show(status, policyDn)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(UiDispatcher) {
                    summary.text = LdapErrors.describe(e)
                    summary.icon = AllIcons.General.Error
                    updateButtons()
                }
            }
        }
    }

    private fun show(status: AccountStatus, policyDn: String?) {
        val problem = status.disabled || status.locked || status.passwordExpired || status.accountExpired
        summary.text = status.summary
        summary.icon = when {
            problem -> AllIcons.General.Error
            status.mustChangePassword -> AllIcons.General.Warning
            else -> AllIcons.General.InspectionsOK
        }
        flavorLabel.text = "Detected: ${status.flavor}" + (policyDn?.let { " — policy: $it" } ?: "")
        facts.facts = status.facts
        disableButton.text = if (status.disabled) "Enable" else "Disable"
        updateButtons()
    }

    private fun updateButtons() {
        val writable = entry != null && session?.config?.readOnly == false
        unlockButton.isEnabled = writable && status?.locked == true
        disableButton.isEnabled = writable
        forceButton.isEnabled = writable && status?.mustChangePassword != true
    }

    private fun change(title: String, modifications: (Entry, AccountFlavor) -> List<Modification>) {
        val session = session ?: return
        val entry = entry ?: return
        val mods = modifications(entry, flavor)
        if (mods.isEmpty()) return
        val ldif = ChangeRecords.modify(dn, mods)
        if (!ChangePreviewDialog(project, title, "The following change will be sent to ${session.config.displayName}:", ldif, optional = false, okText = title).showAndGet()) return
        // OpenLDAP only lets administrators change ppolicy operational attributes with the Relax Rules control.
        val controls = if (session.rootDse?.supportsControl(AccountTools.RELAX_RULES_OID) == true &&
            mods.any { it.attributeName.startsWith("pwd", ignoreCase = true) }
        ) listOf(Control(AccountTools.RELAX_RULES_OID, false)) else emptyList()
        scope.launch {
            try {
                session.modify(dn, mods, controls)
                LdapEvents.fire { it.entryModified(configId, dn) }
                withContext(UiDispatcher) { reload() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(UiDispatcher) {
                    summary.text = "$title failed: ${LdapErrors.describe(e)}"
                    summary.icon = AllIcons.General.Error
                }
            }
        }
    }

    private class FactsModel : AbstractTableModel() {
        var facts: List<AccountFact> = emptyList()
            set(value) {
                field = value
                fireTableDataChanged()
            }

        override fun getRowCount() = facts.size
        override fun getColumnCount() = 2
        override fun getColumnName(column: Int) = if (column == 0) "Property" else "Value"
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
            facts[rowIndex].let { if (columnIndex == 0) it.label else it.value }
    }

    private inner class FactRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val fact = facts.facts.getOrNull(row) ?: return
            val style = if (column == 1 && fact.warning) SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.RED)
            else SimpleTextAttributes.REGULAR_ATTRIBUTES
            append(value?.toString().orEmpty(), style)
        }
    }
}
