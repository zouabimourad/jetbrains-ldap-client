package com.mouradzouabi.ldapclient.ui.log

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.mouradzouabi.ldapclient.core.LdapOperation
import com.mouradzouabi.ldapclient.model.LdapSettings
import com.mouradzouabi.ldapclient.service.LdapNotifications
import com.mouradzouabi.ldapclient.service.LdapOperationLog
import com.mouradzouabi.ldapclient.service.LdapProjectService
import com.mouradzouabi.ldapclient.ui.EntryOperations
import com.mouradzouabi.ldapclient.ui.LdifViewer
import com.mouradzouabi.ldapclient.ui.TableGrid
import com.mouradzouabi.ldapclient.ui.toolwindow.LdapResultsToolWindowFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import kotlin.coroutines.cancellation.CancellationException

/** Lists the write operations sent to the servers during this IDE session, newest first, with their LDIF. */
class OperationLogPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val log = LdapOperationLog.getInstance()
    private val model = Model()
    private val table = JBTable(model)
    private val details = LdifViewer(project, this)
    private val refreshQueued = AtomicBoolean(false)
    private var failuresOnly = false

    init {
        TableGrid.apply(table)
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.emptyText.text = "No changes sent to LDAP servers yet"
        table.setDefaultRenderer(Any::class.java, Renderer())
        widths(120, 140, 90, 420, 220)
        table.columnModel.getColumn(2).minWidth = JBUI.scale(80)
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showDetails() }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                openSelected()
                return true
            }
        }.installOn(table)

        val actions = DefaultActionGroup().apply {
            add(action("Open Entry", AllIcons.Actions.EditSource, { selected().size == 1 }) { openSelected() })
            add(action("Copy LDIF", AllIcons.Actions.Copy, { selected().isNotEmpty() }) { copyLdif() })
            add(action("Export as LDIF Script…", AllIcons.ToolbarDecorator.Export, { model.rowCount > 0 }) { export() })
            addSeparator()
            add(object : ToggleAction("Show Failures Only", null, AllIcons.General.Error) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = failuresOnly
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    failuresOnly = state
                    refresh()
                }
            })
            add(action("Clear", AllIcons.Actions.GC, { model.rowCount > 0 }) { log.clear() })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("LdapOperationLog", actions, true)
        toolbar.targetComponent = table
        setToolbar(toolbar.component)
        PopupHandler.installPopupMenu(table, actions, "LdapOperationLogPopup")

        val splitter = OnePixelSplitter(false, 0.6f)
        splitter.firstComponent = ScrollPaneFactory.createScrollPane(table, true)
        splitter.secondComponent = details.component
        setContent(splitter)

        log.addListener(this) {
            if (refreshQueued.compareAndSet(false, true)) {
                ApplicationManager.getApplication().invokeLater({
                    refreshQueued.set(false)
                    refresh()
                }, ModalityState.any()) { project.isDisposed }
            }
        }
        refresh()
    }

    private fun widths(vararg widths: Int) {
        widths.forEachIndexed { i, w -> table.columnModel.getColumn(i).preferredWidth = JBUI.scale(w) }
    }

    private fun refresh() {
        val selectedKeys = selected().toSet()
        model.operations = log.snapshot().asReversed().filter { !failuresOnly || !it.succeeded }
        model.fireTableDataChanged()
        model.operations.forEachIndexed { row, op ->
            if (op in selectedKeys) table.selectionModel.addSelectionInterval(row, row)
        }
        showDetails()
    }

    private fun selected(): List<LdapOperation> = table.selectedRows.map { model.operations[it] }

    private fun showDetails() {
        val operation = selected().firstOrNull()
        details.text = when {
            operation == null -> ""
            operation.succeeded -> "# ${header(operation)}\n${operation.ldif}"
            else -> "# ${header(operation)}\n# FAILED: ${operation.error?.replace("\n", "\n# ")}\n${operation.ldif}"
        }
    }

    private fun header(operation: LdapOperation) =
        "${TIME.format(operation.time)} ${operation.connectionName} (${operation.durationMillis} ms)"

    private fun openSelected() {
        val operation = selected().singleOrNull() ?: return
        if (LdapSettings.getInstance().find(operation.connectionId) == null) return
        EntryOperations.open(project, operation.connectionId, operation.dn)
    }

    private fun copyLdif() {
        CopyPasteManager.getInstance().setContents(StringSelection(script(selected().sortedBy { it.time })))
    }

    private fun script(operations: List<LdapOperation>) = buildString {
        append("# LDAP operation log — password values are masked\nversion: 1\n")
        for (operation in operations) {
            append("\n# ").append(header(operation))
            if (!operation.succeeded) append(" — FAILED: ").append(operation.error?.lines()?.firstOrNull())
            append('\n').append(operation.ldif.trimEnd()).append('\n')
        }
    }

    private fun export() {
        val operations = (selected().takeIf { it.size > 1 } ?: model.operations).sortedBy { it.time }
        val descriptor = FileSaverDescriptor("Export Operation Log", "Save the logged changes as an LDIF change script", "ldif")
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save("ldap-operations.ldif")?.file ?: return
        val text = script(operations)
        LdapProjectService.getInstance(project).scope.launch(Dispatchers.IO) {
            try {
                target.writeText(text)
                LdapNotifications.info(project, "Operation log", "Exported ${operations.size} operations to ${target.path}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LdapNotifications.error(project, "Operation log export failed", e.message ?: e.toString())
            }
        }
    }

    private fun action(text: String, icon: javax.swing.Icon, enabled: () -> Boolean, perform: () -> Unit) =
        object : DumbAwareAction(text, null, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }

            override fun actionPerformed(e: AnActionEvent) = perform()
        }

    override fun dispose() {}

    private class Model : AbstractTableModel() {
        var operations: List<LdapOperation> = emptyList()
        override fun getRowCount() = operations.size
        override fun getColumnCount() = COLUMNS.size
        override fun getColumnName(column: Int) = COLUMNS[column]
        override fun getValueAt(row: Int, column: Int): Any {
            val op = operations[row]
            return when (column) {
                0 -> TIME.format(op.time)
                1 -> op.connectionName
                2 -> op.type
                3 -> op.dn
                else -> op.error?.lines()?.firstOrNull() ?: "OK"
            }
        }
    }

    private inner class Renderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val operation = model.operations.getOrNull(row) ?: return
            border = JBUI.Borders.empty(0, 4)
            when (column) {
                4 -> {
                    icon = if (operation.succeeded) AllIcons.General.InspectionsOK else AllIcons.General.Error
                    append(value.toString(), if (operation.succeeded) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.ERROR_ATTRIBUTES)
                }
                else -> append(value.toString(), if (column == 0) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
    }

    companion object {
        private val COLUMNS = listOf("Time", "Connection", "Operation", "DN", "Result")
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
        private const val TITLE = "Operation Log"

        fun show(project: Project) {
            val toolWindow = LdapResultsToolWindowFactory.toolWindow(project) ?: return
            val existing = toolWindow.contentManager.contents.firstOrNull { it.component is OperationLogPanel }
            if (existing != null) {
                toolWindow.isAvailable = true
                toolWindow.contentManager.setSelectedContent(existing)
                toolWindow.activate(null)
                return
            }
            val panel = OperationLogPanel(project)
            LdapResultsToolWindowFactory.show(project, panel, TITLE, "Changes sent to LDAP servers during this session", panel)
        }
    }
}
