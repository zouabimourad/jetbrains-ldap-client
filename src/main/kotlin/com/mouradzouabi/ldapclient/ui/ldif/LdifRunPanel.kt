package com.mouradzouabi.ldapclient.ui.ldif

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.mouradzouabi.ldapclient.core.CountingReader
import com.mouradzouabi.ldapclient.core.LdapErrors
import com.mouradzouabi.ldapclient.core.LdifRecordResult
import com.mouradzouabi.ldapclient.core.LdifRunOptions
import com.mouradzouabi.ldapclient.core.LdifScript
import com.mouradzouabi.ldapclient.core.LdifStatus
import com.mouradzouabi.ldapclient.model.BulkChanges
import com.mouradzouabi.ldapclient.model.LdapEvents
import com.mouradzouabi.ldapclient.service.LdapConnectionManager
import com.mouradzouabi.ldapclient.service.LdapProjectService
import com.mouradzouabi.ldapclient.ui.EntryOperations
import com.mouradzouabi.ldapclient.ui.UiDispatcher
import com.mouradzouabi.ldapclient.ui.TableGrid
import com.mouradzouabi.ldapclient.ui.toolwindow.LdapResultsToolWindowFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs an LDIF script in the background and streams per-record results into a table.
 * Results are batched to the EDT; the tree and open editors are refreshed once at the end.
 */
class LdifRunPanel(
    private val project: Project,
    val source: LdifSource,
    val configId: String,
    private val connectionName: String,
    private val options: LdifRunOptions,
) : SimpleToolWindowPanel(true, true), Disposable {

    private val scope = LdapProjectService.getInstance(project).childScope(this)
    private val model = ResultsModel()
    private val table = JBTable(model)
    private val status = JBLabel()
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    private val selectedResult: LdifRecordResult? get() = table.selectedRow.takeIf { it >= 0 }?.let { model.visible[it] }

    init {
        TableGrid.apply(table)
        table.emptyText.text = "No records"
        table.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        val samples = listOf("9999", "99999", "modrdn")
        listOf(50, 60, 90, 380, 300).forEachIndexed { i, w ->
            val column = table.columnModel.getColumn(i)
            column.preferredWidth = JBUI.scale(w)
            samples.getOrNull(i)?.let { sample ->
                val header = table.tableHeader.defaultRenderer
                    .getTableCellRendererComponent(table, column.headerValue, false, false, -1, i).preferredSize.width
                val fixed = maxOf(header, table.getFontMetrics(table.font).stringWidth(sample)) + JBUI.scale(16)
                column.minWidth = fixed
                column.maxWidth = maxOf(fixed, JBUI.scale(w))
                column.preferredWidth = fixed
            }
        }
        table.columnModel.getColumn(COLUMN_RESULT).cellRenderer = ResultRenderer()
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (source.file != null) goToSource() else openEntry()
                return true
            }
        }.installOn(table)

        val goToSource = action("Go to Record", AllIcons.Actions.EditSource, { source.file != null && selectedResult != null }) { goToSource() }
        val openEntry = action("Open Entry", AllIcons.Nodes.DataSchema, {
            selectedResult?.let { it.status == LdifStatus.OK && it.operation != "delete" } == true
        }) { openEntry() }
        val copyDn = action("Copy DN", AllIcons.Actions.Copy, { table.selectedRow >= 0 }) {
            EntryOperations.copyDn(table.selectedRows.joinToString("\n") { model.visible[it].dn })
        }
        val failuresOnly = object : DumbAwareToggleAction("Show Failures Only", null, AllIcons.General.Error) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = model.failuresOnly
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                model.failuresOnly = state
            }
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("LdapLdifRun", DefaultActionGroup().apply {
            add(action("Rerun", AllIcons.Actions.Rerun, { !isRunning }) { run() })
            add(action("Stop", AllIcons.Actions.Suspend, { isRunning }) { job?.cancel() })
            addSeparator()
            add(failuresOnly)
            add(goToSource)
            add(openEntry)
            add(copyDn)
        }, true)
        toolbar.targetComponent = table
        setToolbar(toolbar.component)
        PopupHandler.installPopupMenu(table, DefaultActionGroup(goToSource, openEntry, copyDn), "LdapLdifRunPopup")

        val content = JPanel(BorderLayout())
        content.add(ScrollPaneFactory.createScrollPane(table, true), BorderLayout.CENTER)
        status.border = JBUI.Borders.empty(2, 6)
        content.add(status, BorderLayout.SOUTH)
        setContent(content)
    }

    private fun action(text: String, icon: Icon, enabled: () -> Boolean, perform: () -> Unit) =
        object : DumbAwareAction(text, null, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }

            override fun actionPerformed(e: AnActionEvent) = perform()
        }

    private fun goToSource() {
        val result = selectedResult ?: return
        val file = source.file ?: return
        OpenFileDescriptor(project, file, source.lineOffset + result.line, 0).navigate(true)
    }

    private fun openEntry() {
        val result = selectedResult ?: return
        EntryOperations.open(project, configId, result.newDn ?: result.dn)
    }

    fun run() {
        job?.cancel()
        model.clear()
        val verb = if (options.dryRun) "Validating" else "Running"
        status.icon = AnimatedIcon.Default.INSTANCE
        status.text = "$verb…"
        val started = System.currentTimeMillis()
        job = scope.launch(UiDispatcher) {
            val queue = ConcurrentLinkedQueue<LdifRecordResult>()
            val changes = BulkChanges()
            fun flush() {
                if (queue.isEmpty()) return
                val batch = ArrayList<LdifRecordResult>()
                while (true) batch += queue.poll() ?: break
                model.append(batch)
                status.text = "$verb… ${model.total} records, ${model.failed} failed"
            }
            val flusher = launch {
                while (isActive) {
                    delay(250)
                    flush()
                }
            }
            try {
                val session = LdapConnectionManager.getInstance().connect(configId)
                val s = withBackgroundProgress(project, "$verb LDIF ${source.title} on $connectionName", cancellable = true) {
                    reportRawProgress { reporter ->
                        withContext(Dispatchers.IO) {
                            val (raw, size) = source.open()
                            val reader = CountingReader(raw)
                            LdifScript.run(session, reader, options) { result ->
                                queue.add(result)
                                if (result.status == LdifStatus.OK) record(changes, result)
                                if (size > 0) reporter.fraction((reader.count.toDouble() / size).coerceIn(0.0, 1.0))
                                reporter.details("${result.index + 1} records")
                            }
                        }
                    }
                }
                flusher.cancel()
                flush()
                status.icon = when {
                    s.failed > 0 -> AllIcons.General.Error
                    else -> AllIcons.General.InspectionsOK
                }
                status.text = buildString {
                    append("${s.processed} records: ${s.succeeded} ${if (options.dryRun) "valid" else "succeeded"}, ${s.failed} failed")
                    append(" in ${System.currentTimeMillis() - started} ms")
                    if (s.stoppedOnError) append(" — stopped at the first error")
                }
                if (s.failed > 0 && !model.failuresOnly && s.failed < s.processed) {
                    model.visible.indexOfFirst { it.status == LdifStatus.FAILED }.takeIf { it >= 0 }?.let {
                        table.selectionModel.setSelectionInterval(it, it)
                        table.scrollRectToVisible(table.getCellRect(it, 0, true))
                    }
                }
            } catch (e: CancellationException) {
                flusher.cancel()
                flush()
                status.icon = AllIcons.General.Information
                status.text = "Stopped after ${model.total} records"
                throw e
            } catch (e: Exception) {
                flusher.cancel()
                flush()
                status.icon = AllIcons.General.Error
                status.text = LdapErrors.describe(e)
            } finally {
                if (!changes.isEmpty) LdapEvents.fire { it.entriesChanged(configId, changes) }
            }
        }
    }

    private fun record(changes: BulkChanges, result: LdifRecordResult) {
        when (result.operation) {
            "add" -> changes.added(result.dn)
            "modify", "replace" -> changes.modified(result.dn)
            "delete" -> changes.deleted(result.dn)
            "moddn" -> {
                changes.deleted(result.dn)
                result.newDn?.let { changes.added(it) }
            }
        }
    }

    override fun dispose() {}

    private class ResultsModel : AbstractTableModel() {
        private val all = ArrayList<LdifRecordResult>()
        val visible = ArrayList<LdifRecordResult>()
        var failed = 0
            private set
        val total: Int get() = all.size

        var failuresOnly = false
            set(value) {
                field = value
                visible.clear()
                all.filterTo(visible) { accept(it) }
                fireTableDataChanged()
            }

        private fun accept(r: LdifRecordResult) = !failuresOnly || r.status == LdifStatus.FAILED

        fun clear() {
            all.clear()
            visible.clear()
            failed = 0
            fireTableDataChanged()
        }

        fun append(batch: List<LdifRecordResult>) {
            val first = visible.size
            for (r in batch) {
                all += r
                if (r.status == LdifStatus.FAILED) failed++
                if (accept(r)) visible += r
            }
            if (visible.size > first) fireTableRowsInserted(first, visible.size - 1)
        }

        override fun getRowCount() = visible.size
        override fun getColumnCount() = COLUMNS.size
        override fun getColumnName(column: Int) = COLUMNS[column]
        override fun getValueAt(row: Int, column: Int): Any {
            val r = visible[row]
            return when (column) {
                0 -> r.index + 1
                1 -> r.line + 1
                2 -> r.operation
                3 -> if (r.newDn != null) "${r.dn} → ${r.newDn}" else r.dn
                else -> r
            }
        }
    }

    private class ResultRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val r = value as? LdifRecordResult ?: return
            when (r.status) {
                LdifStatus.OK -> {
                    icon = AllIcons.General.InspectionsOK
                    append("OK")
                }
                LdifStatus.VALIDATED -> {
                    icon = AllIcons.General.InspectionsOK
                    append(r.message ?: "Valid", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                LdifStatus.FAILED -> {
                    icon = AllIcons.General.Error
                    append(r.message ?: "Failed", SimpleTextAttributes.ERROR_ATTRIBUTES)
                }
            }
        }
    }

    companion object {
        private val COLUMNS = arrayOf("#", "Line", "Operation", "DN", "Result")
        private const val COLUMN_RESULT = 4

        fun show(project: Project, source: LdifSource, configId: String, connectionName: String, options: LdifRunOptions) {
            val manager = LdapResultsToolWindowFactory.toolWindow(project)?.contentManager ?: return
            // Replace a finished tab for the same script and connection instead of piling up tabs.
            manager.contents.filter {
                val panel = it.component as? LdifRunPanel
                panel != null && panel.source.key == source.key && panel.configId == configId && !panel.isRunning
            }.forEach { manager.removeContent(it, true) }

            val panel = LdifRunPanel(project, source, configId, connectionName, options)
            val prefix = if (options.dryRun) "Validate" else "LDIF"
            val title = "$prefix: ${source.title}".let { if (it.length > 40) it.take(39) + "…" else it }
            LdapResultsToolWindowFactory.show(project, panel, title, "${source.title} on $connectionName", panel)
            panel.run()
        }
    }
}
