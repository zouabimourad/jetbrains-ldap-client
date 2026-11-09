package com.mouradzouabi.ldapclient.ui.search

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.mouradzouabi.ldapclient.model.LdapSettings
import com.mouradzouabi.ldapclient.ui.EntryRef
import com.mouradzouabi.ldapclient.ui.EntryTools
import com.mouradzouabi.ldapclient.ui.dialogs.BulkModifyDialog
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.unboundid.ldap.sdk.Filter
import com.unboundid.ldap.sdk.SearchResultEntry
import com.unboundid.ldif.LDIFWriter
import com.mouradzouabi.ldapclient.core.AttributeKinds
import com.mouradzouabi.ldapclient.core.LdapErrors
import com.mouradzouabi.ldapclient.core.LdapSchema
import com.mouradzouabi.ldapclient.core.SearchSpec
import com.mouradzouabi.ldapclient.service.LdapConnectionManager
import com.mouradzouabi.ldapclient.service.LdapNotifications
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
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JViewport
import javax.swing.table.AbstractTableModel
import kotlin.coroutines.cancellation.CancellationException

/**
 * Streams search results into a table. Entries arrive on the LDAP reader thread, are queued and
 * flushed to the table in batches on the EDT, so huge result sets never freeze the UI.
 */
class SearchResultsPanel(private val project: Project, params: SearchParams) :
    SimpleToolWindowPanel(true, true), Disposable {

    var params: SearchParams = params
        private set
    private val scope = LdapProjectService.getInstance(project).childScope(this)
    private var model = ResultsModel(params.attributes)
    private val table = JBTable(model)
    private val header = JBLabel()
    private val status = JBLabel()
    private var job: Job? = null
    /** Incremented by every run so that a cancelled run doesn't touch the table or status of the next one. */
    private var generation = 0
    private var lastColumnFitWidth = 0
    private var schema = LdapSchema(null)

    init {
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        TableGrid.apply(table)
        table.emptyText.text = "No results"
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                openSelected()
                return true
            }
        }.installOn(table)

        val toolbar = ActionManager.getInstance().createActionToolbar("LdapSearchResults", DefaultActionGroup().apply {
            add(action("Rerun", AllIcons.Actions.Rerun, { job?.isActive != true }) { run() })
            add(action("Stop", AllIcons.Actions.Suspend, { job?.isActive == true }) { job?.cancel() })
            add(action("Edit Search…", AllIcons.Actions.Edit, { true }) { editSearch() })
            add(action("Save Search…", AllIcons.Actions.MenuSaveall, { true }) { saveSearch() })
            addSeparator()
            add(action("Open Entry", AllIcons.Actions.EditSource, { table.selectedRow >= 0 }) { openSelected() })
            add(action("Copy DN", AllIcons.Actions.Copy, { table.selectedRow >= 0 }) {
                EntryOperations.copyDn(table.selectedRows.joinToString("\n") { model.entries[it].dn })
            })
            add(action("Export to LDIF…", AllIcons.ToolbarDecorator.Export, { model.rowCount > 0 && job?.isActive != true }) { exportLdif() })
            addSeparator()
            add(bulkModifyAction)
            add(compareAction)
        }, true)
        toolbar.targetComponent = table
        setToolbar(toolbar.component)
        val content = JPanel(BorderLayout())
        val scrollPane = ScrollPaneFactory.createScrollPane(table, true)
        scrollPane.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = fillLastColumn()
        })
        content.add(scrollPane, BorderLayout.CENTER)
        header.foreground = UIUtil.getContextHelpForeground()
        header.border = JBUI.Borders.empty(3, 6)
        content.add(header, BorderLayout.NORTH)
        updateHeader()
        status.border = JBUI.Borders.empty(2, 6)
        content.add(status, BorderLayout.SOUTH)
        setContent(content)
        PopupHandler.installPopupMenu(table, DefaultActionGroup().apply {
            add(action("Open Entry", AllIcons.Actions.EditSource, { table.selectedRow >= 0 }) { openSelected() })
            add(action("Copy DN", AllIcons.Actions.Copy, { table.selectedRow >= 0 }) {
                EntryOperations.copyDn(table.selectedRows.joinToString("\n") { model.entries[it].dn })
            })
            addSeparator()
            add(compareAction)
            add(bulkModifyAction)
        }, "LdapSearchResultsPopup")
    }

    private val bulkModifyAction get() = action("Bulk Modify…", AllIcons.Actions.EditScheme, {
        model.rowCount > 0 && job?.isActive != true && LdapSettings.getInstance().find(params.configId)?.readOnly == false
    }) { bulkModify() }

    private val compareAction get() = action("Compare Selected Entries", AllIcons.Actions.Diff, { table.selectedRowCount == 2 }) {
        val (a, b) = table.selectedRows.map { EntryRef(params.configId, model.entries[it].dn) }
        EntryTools.compare(project, a, b)
    }

    private fun bulkModify() {
        val selected = table.selectedRows.map { model.entries[it].dn }
        val all = model.entries.map { it.dn }
        BulkModifyDialog(project, params.configId, params.connectionName, schema, selected, all).show()
    }

    private fun action(text: String, icon: javax.swing.Icon, enabled: () -> Boolean, perform: () -> Unit) =
        object : DumbAwareAction(text, null, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }

            override fun actionPerformed(e: AnActionEvent) = perform()
        }

    /** Sizes the columns to their header and to the values of the first rows; the last column fills the remaining space. */
    private fun fitColumns() {
        val columns = table.columnModel
        val header = table.tableHeader.defaultRenderer
        val sample = minOf(table.rowCount, FIT_SAMPLE_ROWS)
        for (c in 0 until columns.columnCount) {
            val column = columns.getColumn(c)
            var width = header.getTableCellRendererComponent(table, column.headerValue, false, false, -1, c).preferredSize.width
            for (r in 0 until sample) {
                width = maxOf(width, table.prepareRenderer(table.getCellRenderer(r, c), r, c).preferredSize.width)
            }
            column.preferredWidth = minOf(width + JBUI.scale(12), JBUI.scale(MAX_FIT_WIDTH))
            column.width = column.preferredWidth
        }
        lastColumnFitWidth = columns.getColumn(columns.columnCount - 1).preferredWidth
        fillLastColumn()
    }

    // JTable.doLayout resets widths to the preferred widths, so the stretch must go into the preferred width.
    private fun fillLastColumn() {
        val columns = table.columnModel
        if (columns.columnCount == 0) return
        val available = (table.parent as? JViewport)?.width ?: return
        val others = (0 until columns.columnCount - 1).sumOf { columns.getColumn(it).preferredWidth }
        val last = columns.getColumn(columns.columnCount - 1)
        last.preferredWidth = maxOf(lastColumnFitWidth, available - others)
    }

    private fun openSelected() {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return
        EntryOperations.open(project, params.configId, model.entries[row].dn)
    }

    private fun editSearch() {
        val dialog = SearchDialog(project, params.configId, params.baseDn, params)
        if (dialog.showAndGet()) rerun(dialog.params)
    }

    private fun saveSearch() {
        val name = SavedSearchPrompt.save(this, params.toSavedSearch(""), params.savedName, params.filter) ?: return
        params = params.copy(savedName = name)
        updateHeader()
    }

    /** Runs [newParams] in this tab, replacing the current search. */
    fun rerun(newParams: SearchParams) {
        job?.cancel()
        if (newParams.attributes != params.attributes) {
            model = ResultsModel(newParams.attributes)
            table.model = model
        }
        params = newParams
        updateHeader()
        run()
    }

    private fun updateHeader() {
        header.text = params.summary
        header.toolTipText = params.summary
        LdapResultsToolWindowFactory.toolWindow(project)?.contentManager?.getContent(this)?.let {
            it.displayName = params.title
            it.description = params.summary
        }
    }

    fun run() {
        job?.cancel()
        val runGeneration = ++generation
        val params = params
        val model = model
        model.clear()
        status.icon = AnimatedIcon.Default.INSTANCE
        status.text = "Searching…"
        val started = System.currentTimeMillis()
        job = scope.launch(UiDispatcher) {
            val queue = ConcurrentLinkedQueue<SearchResultEntry>()
            fun current() = runGeneration == generation
            fun flush() {
                if (queue.isEmpty() || !current()) return
                val batch = ArrayList<SearchResultEntry>()
                while (true) batch += queue.poll() ?: break
                val before = model.rowCount
                model.append(batch, schema)
                if (before < FIT_SAMPLE_ROWS) fitColumns()
                status.text = "Searching… ${model.rowCount} entries"
            }
            val flusher = launch {
                while (isActive) {
                    delay(250)
                    flush()
                }
            }
            try {
                val session = LdapConnectionManager.getInstance().connect(params.configId)
                schema = session.schemaAsync()
                val spec = SearchSpec(
                    baseDn = params.baseDn.ifEmpty { session.baseDns.first() },
                    scope = params.scope,
                    filter = Filter.create(params.filter),
                    attributes = params.attributes,
                    timeLimitSeconds = session.config.responseTimeoutSeconds,
                )
                val outcome = session.search(spec, params.sizeLimit) { queue.add(it) }
                flusher.cancel()
                flush()
                if (!current()) return@launch
                status.icon = if (outcome.truncated) AllIcons.General.Warning else AllIcons.General.InspectionsOK
                status.text = buildString {
                    append("${outcome.count} entries in ${System.currentTimeMillis() - started} ms")
                    if (outcome.truncated) append(" — size limit reached, more entries exist")
                }
            } catch (e: CancellationException) {
                flusher.cancel()
                flush()
                if (current()) {
                    status.icon = AllIcons.General.Information
                    status.text = "Stopped — ${model.rowCount} entries"
                }
                throw e
            } catch (e: Exception) {
                flusher.cancel()
                flush()
                if (current()) {
                    status.icon = AllIcons.General.Error
                    status.text = LdapErrors.describe(e)
                }
            }
        }
    }

    private fun exportLdif() {
        val descriptor = FileSaverDescriptor("Export LDIF", "Export search results as LDIF", "ldif")
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save("search-results.ldif")?.file ?: return
        val entries = model.entries.toList()
        scope.launch(Dispatchers.IO) {
            try {
                LDIFWriter(target).use { writer -> entries.forEach { writer.writeEntry(it) } }
                LdapNotifications.info(project, "LDIF export", "Exported ${entries.size} entries to ${target.path}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LdapNotifications.error(project, "LDIF export failed", e.message ?: e.toString())
            }
        }
    }

    override fun dispose() {}

    private class ResultsModel(requested: List<String>) : AbstractTableModel() {
        private val dynamicColumns = requested.any { it == "*" || it == "+" }
        private val columns = mutableListOf("DN").apply {
            if (!dynamicColumns) addAll(requested.filter { it != "1.1" })
        }
        private val columnKeys = columns.map { it.lowercase() }.toMutableSet()
        val entries = ArrayList<SearchResultEntry>()
        private val cells = ArrayList<Map<String, String>>()

        fun clear() {
            entries.clear()
            cells.clear()
            fireTableDataChanged()
        }

        fun append(batch: List<SearchResultEntry>, schema: LdapSchema) {
            var structureChanged = false
            val first = entries.size
            for (entry in batch) {
                val row = HashMap<String, String>()
                for (attr in entry.attributes) {
                    val values = attr.valueByteArrays.toList()
                    val kind = AttributeKinds.classify(attr.name, schema, values)
                    row[attr.name.lowercase()] = values.joinToString(" | ") { AttributeKinds.display(kind, it) }
                    if (dynamicColumns && columnKeys.size < MAX_DYNAMIC_COLUMNS && columnKeys.add(attr.name.lowercase())) {
                        columns += attr.name
                        structureChanged = true
                    }
                }
                entries += entry
                cells += row
            }
            if (structureChanged) fireTableStructureChanged() else fireTableRowsInserted(first, entries.size - 1)
        }

        override fun getRowCount() = entries.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getValueAt(row: Int, column: Int): Any =
            if (column == 0) entries[row].dn else cells[row][columns[column].lowercase()].orEmpty()
    }

    companion object {
        private const val MAX_DYNAMIC_COLUMNS = 40
        private const val FIT_SAMPLE_ROWS = 200
        private const val MAX_FIT_WIDTH = 420

        /** Shows the results of [params], reusing the tab of the same saved search or of an identical search. */
        fun show(project: Project, params: SearchParams) {
            val toolWindow = LdapResultsToolWindowFactory.toolWindow(project)
            val existing = toolWindow?.contentManager?.contents?.firstOrNull { content ->
                val shown = (content.component as? SearchResultsPanel)?.params ?: return@firstOrNull false
                if (params.savedName != null) shown.savedName == params.savedName else shown.savedName == null && shown.sameQuery(params)
            }
            if (existing != null) {
                toolWindow.contentManager.setSelectedContent(existing)
                toolWindow.activate(null)
                (existing.component as SearchResultsPanel).rerun(params)
                return
            }
            val panel = SearchResultsPanel(project, params)
            LdapResultsToolWindowFactory.show(project, panel, params.title, params.summary, panel)
            panel.run()
        }
    }
}
