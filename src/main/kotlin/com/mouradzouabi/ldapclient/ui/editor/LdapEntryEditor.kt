package com.mouradzouabi.ldapclient.ui.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.openapi.vcs.FileStatus
import com.unboundid.ldif.LDIFWriter
import com.mouradzouabi.ldapclient.core.AccountTools
import com.mouradzouabi.ldapclient.core.AttributeKind
import com.mouradzouabi.ldapclient.core.AttributeKinds
import com.mouradzouabi.ldapclient.core.GroupMembership
import com.mouradzouabi.ldapclient.ui.membership.MembershipNavigator
import com.intellij.openapi.keymap.KeymapUtil
import com.mouradzouabi.ldapclient.core.LdapDn
import com.mouradzouabi.ldapclient.core.LdapErrors
import com.mouradzouabi.ldapclient.core.LdapSchema
import com.mouradzouabi.ldapclient.core.SchemaIndex
import com.mouradzouabi.ldapclient.core.SchemaKind
import com.mouradzouabi.ldapclient.model.LdapEvents
import com.mouradzouabi.ldapclient.model.BulkChanges
import com.mouradzouabi.ldapclient.model.LdapListener
import com.mouradzouabi.ldapclient.model.LdapSettings
import com.mouradzouabi.ldapclient.service.LdapConnectionManager
import com.mouradzouabi.ldapclient.service.LdapNotifications
import com.mouradzouabi.ldapclient.service.LdapProjectService
import com.mouradzouabi.ldapclient.ui.EntryOperations
import com.mouradzouabi.ldapclient.ui.EntryTools
import com.mouradzouabi.ldapclient.ui.schema.LdapSchemaVirtualFile
import com.mouradzouabi.ldapclient.ui.UiDispatcher
import com.mouradzouabi.ldapclient.ui.dialogs.AddAttributeDialog
import com.mouradzouabi.ldapclient.ui.dialogs.BinaryValueDialog
import com.mouradzouabi.ldapclient.ui.dialogs.PasswordValueDialog
import com.mouradzouabi.ldapclient.ui.dialogs.TextValueDialog
import com.mouradzouabi.ldapclient.ui.TableGrid
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.io.ByteArrayOutputStream
import java.awt.datatransfer.StringSelection
import javax.swing.DefaultCellEditor
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.table.TableCellEditor
import kotlin.coroutines.cancellation.CancellationException

/**
 * Editor tab showing the attributes of one entry. Loading and saving run in coroutines on background
 * threads; edits are applied to a local working copy and sent as one modify request on save.
 */
class LdapEntryEditor(private val project: Project, private val file: LdapEntryVirtualFile) : UserDataHolderBase(), FileEditor {
    private val scope = LdapProjectService.getInstance(project).childScope(this)
    private val settings = LdapSettings.getInstance()
    private val model = AttributeTableModel()
    private val changeSupport = PropertyChangeSupport(this)
    private var lastModified = false
    private var loadJob: Job? = null
    private var deleted = false

    private val booleanEditor = DefaultCellEditor(ComboBox(arrayOf("TRUE", "FALSE")))
    private val table = object : JBTable(model) {
        override fun getCellEditor(row: Int, column: Int): TableCellEditor {
            val attributes = this@LdapEntryEditor.model
            val r = attributes.rows.getOrNull(row)
            if (column == 1 && r != null && attributes.kind(r.attribute) == AttributeKind.BOOLEAN) return booleanEditor
            return super.getCellEditor(row, column)
        }

        /**
         * Spreadsheet-like grid where a multi-valued attribute looks like a merged cell: lines between its values span the value
         * column only, a full-width line follows its last value, and a stronger one precedes the operational attributes.
         */
        override fun paintComponent(g: java.awt.Graphics) {
            super.paintComponent(g)
            val attributes = this@LdapEntryEditor.model
            val clip = g.clipBounds ?: return
            val firstRow = rowAtPoint(java.awt.Point(0, clip.y)).takeIf { it >= 0 } ?: 0
            val lastRow = rowAtPoint(java.awt.Point(0, clip.y + clip.height)).takeIf { it >= 0 } ?: (rowCount - 1)
            // The renderer paints the focus border inside the focused cell; keep the separator from covering its bottom edge.
            val focusRow = selectionModel.leadSelectionIndex
            val focusColumn = columnModel.selectionModel.leadSelectionIndex
            val focusCell = if (isFocusOwner && focusRow >= 0 && focusColumn >= 0 && !isEditing) getCellRect(focusRow, focusColumn, true) else null
            val g2 = g.create() as java.awt.Graphics2D
            try {
                for (i in firstRow..lastRow) {
                    val r = attributes.rows.getOrNull(i) ?: continue
                    val next = attributes.rows.getOrNull(i + 1)
                    val y = i * rowHeight + rowHeight - 1
                    val sectionBreak = r.last && next != null && attributes.isOperational(next.attribute) && !attributes.isOperational(r.attribute)
                    g2.color = if (sectionBreak) TableGrid.SECTION_COLOR else TableGrid.LINE_COLOR
                    val top = if (sectionBreak) y - 1 else y
                    val height = if (sectionBreak) 2 else 1
                    val left = maxOf(clip.x, if (r.last) 0 else getCellRect(i, 1, true).x)
                    val right = clip.x + clip.width
                    if (left >= right) continue
                    if (focusCell != null && i == focusRow) {
                        if (focusCell.x > left) g2.fillRect(left, top, focusCell.x - left, height)
                        val after = maxOf(focusCell.x + focusCell.width, left)
                        if (after < right) g2.fillRect(after, top, right - after, height)
                    } else {
                        g2.fillRect(left, top, right - left, height)
                    }
                }
            } finally {
                g2.dispose()
            }
        }
    }
    private val dnLabel = JBLabel(file.dn.ifEmpty { "Root DSE" }).apply {
        font = font.deriveFont(java.awt.Font.BOLD)
        setCopyable(true)
    }
    private val statusLabel = JBLabel()
    private val panel = JPanel(BorderLayout())

    internal val isLoaded: Boolean get() = model.loaded

    private val canWrite: () -> Boolean = { model.loaded && !model.readOnly && !deleted }

    private val saveAction = action("Save Changes", AllIcons.Actions.MenuSaveall, { canWrite() && model.isModified }) { save() }
        .also { it.registerCustomShortcutSet(ActionManager.getInstance().getAction("SaveAll").shortcutSet, panel) }
    private val revertAction = action("Revert Changes", AllIcons.Actions.Rollback, { model.isModified }) { model.revert() }
    private val reloadAction = action("Reload", AllIcons.Actions.Refresh) { reload() }
    private val addAttributeAction = action("Add Attribute…", AllIcons.General.Add, canWrite) { addAttribute() }
    private val addValueAction = action("Add Value…", AllIcons.Actions.AddList, { canWrite() && selectedRow?.let { !model.isOperational(it.attribute) } == true }) {
        selectedRow?.let { addValue(it.attribute) }
    }
    private val editValueAction = action("Edit Value…", AllIcons.Actions.Edit, { selectedRow != null }) {
        editRow(table.selectedRow, preferDialog = true)
    }.also { it.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0)), table) }
    private val deleteValueAction = action("Delete Value", AllIcons.General.Remove, { canWrite() && selectedRow?.let(model::canEdit) == true }) {
        deleteSelectedValues()
    }.also { it.registerCustomShortcutSet(CommonShortcuts.getDelete(), table) }
    private val goToDnAction = action("Open Referenced Entry", AllIcons.Actions.Forward, {
        selectedRows().any(::isNavigable)
    }) {
        selectedRows().filter(::isNavigable).take(20).forEach(::navigate)
    }.also {
        val keys = ActionManager.getInstance().getAction(IdeActions.ACTION_GOTO_DECLARATION).shortcutSet.shortcuts
            .filterIsInstance<com.intellij.openapi.actionSystem.KeyboardShortcut>()
        it.registerCustomShortcutSet(CustomShortcutSet(*keys.toTypedArray()), table)
    }
    private val showGroupsAction = action("Show Groups…", AllIcons.Nodes.Related, { model.loaded && file.dn.isNotEmpty() }) {
        MembershipNavigator.showGroups(project, file.configId, file.dn, table)
    }
    private val showMembersAction = action("Show Members…", AllIcons.General.User, { model.loaded && GroupMembership.isGroup(model.objectClasses) }) {
        MembershipNavigator.showMembers(project, file.configId, file.dn, table)
    }
    private val copyValueAction = action("Copy Value", AllIcons.Actions.Copy, { selectedRow != null }) { copySelected() }
    private val copyDnAction = action("Copy DN", null) { EntryOperations.copyDn(file.dn) }
    private val copyLdifAction = action("Copy Entry as LDIF", null, { model.loaded }) { copyAsLdif() }
    private val exportLdifAction = action("Export LDIF…", AllIcons.ToolbarDecorator.Export) {
        com.mouradzouabi.ldapclient.ui.ldif.LdifExporter.export(project, file.configId, file.dn)
    }
    private val renameAction = action("Rename Entry…", null, { canWrite() && file.dn.isNotEmpty() }) {
        EntryOperations.rename(project, file.configId, file.dn)
    }
    private val deleteEntryAction = action("Delete Entry…", AllIcons.General.Delete, canWrite) {
        EntryOperations.delete(project, file.configId, file.dn)
    }
    private val newChildAction = action("New Child Entry…", AllIcons.Actions.New, canWrite) {
        EntryOperations.newChild(project, file.configId, file.dn)
    }
    private val showInSchemaAction = action("Show in Schema", AllIcons.Nodes.DataSchema, { model.loaded }) { showInSchema() }
    private val compareAction = action("Compare With…", AllIcons.Actions.Diff, { model.loaded && file.dn.isNotEmpty() }) {
        EntryTools.compareWith(project, file.configId, file.dn)
    }
    private val duplicateAction = action("Duplicate Entry…", null, { canWrite() && file.dn.isNotEmpty() }) {
        EntryTools.duplicate(project, file.configId, file.dn)
    }
    private val copyMoveAction = action("Copy / Move…", null, { model.loaded && file.dn.isNotEmpty() }) {
        EntryTools.copyOrMove(project, file.configId, file.dn, move = false)
    }
    private val accountStatusAction = object : DumbAwareAction("Account Status…", "Lock, enable and password state of this account", AllIcons.Nodes.Padlock) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = model.loaded && file.dn.isNotEmpty() && AccountTools.isAccount(model.objectClasses)
        }

        override fun actionPerformed(e: AnActionEvent) = EntryTools.accountStatus(project, file.configId, file.dn)
    }
    private val operationalToggle = object : ToggleAction("Show Operational Attributes", null, AllIcons.Actions.ToggleVisibility) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = settings.showOperationalAttributes
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            settings.showOperationalAttributes = state
        }
    }

    init {
        model.showOperational = settings.showOperationalAttributes
        model.onChange = ::onModelChanged
        TableGrid.apply(table, horizontalLines = false)
        table.rowHeight = JBUI.scale(24)
        table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        table.columnModel.getColumn(0).preferredWidth = JBUI.scale(200)
        table.columnModel.getColumn(1).preferredWidth = JBUI.scale(600)
        table.setDefaultRenderer(Any::class.java, AttributeCellRenderer())
        table.columnModel.getColumn(0).cellRenderer = AttributeCellRenderer()
        table.columnModel.getColumn(1).cellRenderer = AttributeCellRenderer()
        table.emptyText.text = "Loading…"
        table.putClientProperty("terminateEditOnFocusLost", true)
        TableSpeedSearch.installOn(table)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val row = table.rowAtPoint(event.point)
                if (row < 0) return false
                if (table.columnAtPoint(event.point) == 1 && model.isCellEditable(row, 1)) return false
                editRow(row, preferDialog = true)
                return true
            }
        }.installOn(table)
        table.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 1 || !isNavigationClick(e)) return
                val row = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                if (table.columnAtPoint(e.point) != 1) return
                model.rows.getOrNull(row)?.takeIf(::isNavigable)?.let {
                    e.consume()
                    navigate(it)
                }
            }
        })
        table.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                val link = isNavigationClick(e) && row >= 0 && table.columnAtPoint(e.point) == 1 &&
                    model.rows.getOrNull(row)?.let(::isNavigable) == true
                table.cursor = if (link) java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR) else java.awt.Cursor.getDefaultCursor()
            }
        })

        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 8)
            add(dnLabel, BorderLayout.NORTH)
            add(JBLabel(file.connectionName).apply {
                foreground = UIUtil.getContextHelpForeground()
            }, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("LdapEntryEditor", createToolbarGroup(), true)
        toolbar.targetComponent = table
        val north = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(toolbar.component, BorderLayout.SOUTH)
        }
        panel.add(north, BorderLayout.NORTH)
        panel.add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
        PopupHandler.installPopupMenu(table, createPopupGroup(), "LdapEntryEditorPopup")

        ApplicationManager.getApplication().messageBus.connect(this).subscribe(LdapListener.TOPIC, object : LdapListener {
            override fun operationalAttributesToggled(show: Boolean) {
                model.showOperational = show
            }

            override fun entryDeleted(configId: String, dn: String) {
                if (configId == file.configId && LdapDn.same(dn, file.dn)) {
                    deleted = true
                    setStatus("This entry has been deleted or renamed", AllIcons.General.Warning)
                }
            }

            override fun entriesChanged(configId: String, changes: BulkChanges) {
                if (configId != file.configId) return
                val key = BulkChanges.normalizeDn(file.dn)
                if (key in changes.deleted) entryDeleted(configId, file.dn)
                else if (key in changes.modified) entryModified(configId, file.dn)
            }

            override fun entryModified(configId: String, dn: String) {
                if (configId == file.configId && LdapDn.same(dn, file.dn) && !model.isModified) reload(confirm = false)
            }

            override fun connectionStateChanged(configId: String) {
                if (configId == file.configId && !model.loaded && LdapConnectionManager.getInstance().isConnected(configId)) {
                    reload(confirm = false)
                }
            }
        })
        reload(confirm = false)
    }

    // ------------------------------------------------------------------ loading / saving

    fun reload(confirm: Boolean = true) {
        if (confirm && model.isModified &&
            Messages.showYesNoDialog(project, "Discard unsaved changes of ${file.dn}?", "Reload Entry", Messages.getQuestionIcon()) != Messages.YES
        ) return
        loadJob?.cancel()
        setStatus("Loading…", AnimatedIcon.Default.INSTANCE)
        loadJob = scope.launch(UiDispatcher) {
            try {
                val session = LdapConnectionManager.getInstance().connect(file.configId)
                val schema = session.schemaAsync()
                // Operational attributes are always fetched; their visibility is a pure UI toggle.
                val entry = session.getEntry(file.dn, listOf("*", "+"))
                if (entry == null) {
                    model.clear()
                    table.emptyText.text = "Entry not found"
                    setStatus("Entry not found (it may have been deleted or renamed)", AllIcons.General.Warning)
                } else {
                    deleted = false
                    model.readOnly = session.config.readOnly
                    model.setEntry(entry, schema)
                    table.emptyText.text = "No attributes"
                    setStatus(if (session.config.readOnly) "Read-only connection" else null, AllIcons.General.Information)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                table.emptyText.text = "Cannot load entry"
                setStatus(LdapErrors.describe(e), AllIcons.General.Error)
            }
        }
    }

    fun save() {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
        val modifications = model.modifications()
        if (modifications.isEmpty()) return
        val confirmed = com.mouradzouabi.ldapclient.ui.dialogs.ChangePreviewDialog.confirm(
            project, "Save ${file.dn}", "The following changes will be sent to the server:",
        ) { com.mouradzouabi.ldapclient.core.ChangeRecords.modify(file.dn, modifications) }
        if (!confirmed) return
        setStatus("Saving…", AnimatedIcon.Default.INSTANCE)
        scope.launch(UiDispatcher) {
            try {
                val session = LdapConnectionManager.getInstance().connect(file.configId)
                withBackgroundProgress(project, "Saving ${file.dn}") { session.modify(file.dn, modifications) }
                reload(confirm = false)
                LdapEvents.fire { it.entryModified(file.configId, file.dn) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = LdapErrors.describe(e)
                setStatus("Save failed: $message", AllIcons.General.Error)
                LdapNotifications.error(project, "Cannot save ${file.dn}", message)
            }
        }
    }

    private fun setStatus(text: String?, icon: Icon?) {
        statusLabel.text = text.orEmpty()
        statusLabel.icon = if (text == null) null else icon
        statusLabel.isVisible = text != null
    }

    private fun onModelChanged() {
        val now = model.isModified
        if (now != lastModified) {
            lastModified = now
            changeSupport.firePropertyChange("modified", !now, now)
        }
    }

    // ------------------------------------------------------------------ editing

    private val selectedRow: AttributeRow? get() = table.selectedRow.takeIf { it >= 0 }?.let { model.rows.getOrNull(it) }

    /** DN values (member, manager, memberOf, …) and posixGroup memberUid values can be opened. */
    private fun isNavigable(row: AttributeRow): Boolean =
        model.kind(row.attribute) == AttributeKind.DN || GroupMembership.isUidMemberAttribute(row.attribute)

    private fun navigate(row: AttributeRow) {
        val value = String(row.value, Charsets.UTF_8).trim()
        if (value.isEmpty()) return
        if (GroupMembership.isUidMemberAttribute(row.attribute)) MembershipNavigator.openMemberUid(project, file.configId, value)
        else EntryOperations.open(project, file.configId, GroupMembership.memberDn(value))
    }

    private fun editRow(rowIndex: Int, preferDialog: Boolean) {
        val row = model.rows.getOrNull(rowIndex) ?: return
        val kind = model.kind(row.attribute)
        val editable = model.canEdit(row) && !deleted
        when (kind) {
            AttributeKind.PASSWORD -> {
                if (!editable) return
                val dialog = PasswordValueDialog(project, row.attribute, row.value)
                if (dialog.showAndGet()) model.setValue(row, dialog.value)
            }
            AttributeKind.BINARY, AttributeKind.IMAGE, AttributeKind.CERTIFICATE, AttributeKind.GUID, AttributeKind.SID -> {
                val dialog = BinaryValueDialog(project, row.attribute, row.value, kind, editable)
                if (dialog.showAndGet() && editable) dialog.value?.let { model.setValue(row, it) }
            }
            else -> {
                val text = String(row.value, Charsets.UTF_8)
                if (!preferDialog && editable && model.isCellEditable(rowIndex, 1)) {
                    table.editCellAt(rowIndex, 1)
                    table.editorComponent?.requestFocusInWindow()
                    return
                }
                val dialog = TextValueDialog(project, "${row.attribute} Value", text, editable, validatorFor(kind))
                if (dialog.showAndGet() && editable) {
                    if (dialog.text.isEmpty()) model.removeValue(row) else model.setValue(row, dialog.text.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    private fun validatorFor(kind: AttributeKind): (String) -> String? = when (kind) {
        AttributeKind.DN -> { s -> if (s.isEmpty() || com.unboundid.ldap.sdk.DN.isValidDN(s)) null else "Invalid DN" }
        AttributeKind.INTEGER -> { s -> if (s.isEmpty() || s.toBigIntegerOrNull() != null) null else "Not an integer" }
        AttributeKind.BOOLEAN -> { s -> if (s.isEmpty() || s == "TRUE" || s == "FALSE") null else "Must be TRUE or FALSE" }
        AttributeKind.TIME -> { s -> if (s.isEmpty() || AttributeKinds.formatTime(s) != null) null else "Expected generalized time, e.g. 20250101120000Z" }
        else -> { _ -> null }
    }

    /** Prompts for a new value of [attribute] using the editor matching its kind. */
    private fun addValue(attribute: String) {
        val kind = model.kind(attribute)
        val bytes: ByteArray? = when (kind) {
            AttributeKind.PASSWORD -> PasswordValueDialog(project, attribute, null).let { if (it.showAndGet()) it.value else null }
            AttributeKind.BINARY, AttributeKind.IMAGE, AttributeKind.CERTIFICATE, AttributeKind.GUID, AttributeKind.SID ->
                BinaryValueDialog(project, attribute, ByteArray(0), kind, true).let { if (it.showAndGet()) it.value else null }
            else -> TextValueDialog(project, "New $attribute Value", "", true, validatorFor(kind))
                .let { if (it.showAndGet()) it.text.takeIf { t -> t.isNotEmpty() }?.toByteArray(Charsets.UTF_8) else null }
        }
        bytes?.let { model.addValue(attribute, it) }
    }

    private fun addAttribute() {
        val dialog = AddAttributeDialog(project, model.schema, model.objectClasses(), model.attributeNames())
        if (dialog.showAndGet()) addValue(dialog.attributeName)
    }

    private fun selectedRows(): List<AttributeRow> {
        val all = model.rows
        val indices: IntArray = table.getSelectedRows()
        return indices.map { i -> all.getOrNull(i) }.filterNotNull()
    }

    private fun deleteSelectedValues() {
        if (table.isEditing) table.cellEditor?.cancelCellEditing()
        val rows = selectedRows().filter { r -> model.canEdit(r) }
        // Remove from the highest index so indices of remaining rows stay valid.
        rows.sortedByDescending { r -> r.index }.forEach { r -> model.removeValue(r) }
    }

    private fun copySelected() {
        val text = selectedRows().joinToString("\n") { r ->
            val kind = model.kind(r.attribute)
            if (kind == AttributeKind.TEXT || kind == AttributeKind.DN || kind.inlineEditable) String(r.value, Charsets.UTF_8)
            else AttributeKinds.display(kind, r.value)
        }
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private fun copyAsLdif() {
        val out = ByteArrayOutputStream()
        LDIFWriter(out).use { it.writeEntry(model.currentEntry(model.showOperational)) }
        CopyPasteManager.getInstance().setContents(StringSelection(out.toString(Charsets.UTF_8)))
    }

    // ------------------------------------------------------------------ actions

    private fun action(text: String, icon: Icon?, enabled: () -> Boolean = { true }, perform: () -> Unit) =
        object : DumbAwareAction(text, null, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }

            override fun actionPerformed(e: AnActionEvent) = perform()
        }

    private fun createToolbarGroup() = DefaultActionGroup().apply {
        add(saveAction)
        add(revertAction)
        add(reloadAction)
        addSeparator()
        add(addAttributeAction)
        add(addValueAction)
        add(editValueAction)
        add(deleteValueAction)
        addSeparator()
        add(goToDnAction)
        add(showGroupsAction)
        add(showMembersAction)
        add(accountStatusAction)
        add(copyValueAction)
        addSeparator()
        add(operationalToggle)
        add(showInSchemaAction)
        add(compareAction)
        addSeparator()
        add(newChildAction)
        add(deleteEntryAction)
        add(exportLdifAction)
    }

    private fun createPopupGroup() = DefaultActionGroup().apply {
        add(editValueAction)
        add(addValueAction)
        add(deleteValueAction)
        add(addAttributeAction)
        addSeparator()
        add(goToDnAction)
        add(showGroupsAction)
        add(showMembersAction)
        add(accountStatusAction)
        add(showInSchemaAction)
        addSeparator()
        add(copyValueAction)
        add(copyDnAction)
        add(copyLdifAction)
        add(compareAction)
        addSeparator()
        add(renameAction)
        add(newChildAction)
        add(duplicateAction)
        add(copyMoveAction)
        add(deleteEntryAction)
    }

    /** Opens the schema browser on the selected attribute type, or on the object class for objectClass values. */
    private fun showInSchema() {
        val row = selectedRow
        when {
            row == null -> LdapSchemaVirtualFile.open(project, file.configId)
            row.attribute.equals("objectClass", true) ->
                LdapSchemaVirtualFile.open(project, file.configId, SchemaKind.OBJECT_CLASS, String(row.value, Charsets.UTF_8))
            else -> LdapSchemaVirtualFile.open(project, file.configId, SchemaKind.ATTRIBUTE_TYPE, LdapSchema.baseName(row.attribute))
        }
    }

    // ------------------------------------------------------------------ rendering

    private inner class AttributeCellRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, rowIndex: Int, column: Int) {
            val row = model.rows.getOrNull(rowIndex) ?: return
            val operational = model.isOperational(row.attribute)
            if (!selected) background = if (row.group % 2 == 1) GROUP_STRIPE else table.background
            ipad = JBUI.insets(0, 6)
            if (column == 0) {
                if (!row.first) return
                val style = when {
                    operational -> SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES
                    model.isAttributeModified(row.attribute) -> SimpleTextAttributes(
                        if (model.isMust(row.attribute)) SimpleTextAttributes.STYLE_BOLD else SimpleTextAttributes.STYLE_PLAIN,
                        FileStatus.MODIFIED.color,
                    )
                    model.isMust(row.attribute) -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                    else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                }
                val allowed = model.isAllowed(row.attribute)
                append(row.attribute, if (allowed) style else style.derive(style.style or SimpleTextAttributes.STYLE_WAVED, null, null, JBColor.RED))
                if (row.count > 1) append("  (${row.count})", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                toolTipText = attributeTooltip(row.attribute, operational, allowed)
                return
            }
            val kind = model.kind(row.attribute)
            icon = when (kind) {
                AttributeKind.PASSWORD -> AllIcons.Nodes.Padlock
                AttributeKind.DN -> AllIcons.Nodes.Related
                AttributeKind.IMAGE, AttributeKind.BINARY, AttributeKind.CERTIFICATE, AttributeKind.GUID, AttributeKind.SID -> AllIcons.Nodes.DataTables
                else -> null
            }
            val style = when {
                operational -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                model.isValueAdded(row) -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, FileStatus.ADDED.color)
                model.isRdnValue(row) -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.foreground())
                else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
            }
            val text = AttributeKinds.display(kind, row.value)
            append(if (text.length > 1000) text.take(1000) + "…" else text, style)
            toolTipText = when {
                model.isRdnValue(row) -> "Part of the RDN — use Rename Entry to change it"
                column == 1 && isNavigable(row) -> "${if (com.intellij.openapi.util.SystemInfo.isMac) "⌘" else "Ctrl+"}Click or " +
                    KeymapUtil.getFirstKeyboardShortcutText(IdeActions.ACTION_GOTO_DECLARATION) + " to open"
                else -> null
            }
        }
    }

    private fun attributeTooltip(attribute: String, operational: Boolean, allowed: Boolean): String {
        val schema = model.schema
        val type = schema.attributeType(attribute)
        val esc = com.intellij.openapi.util.text.StringUtil::escapeXmlEntities
        return buildString {
            append("<html><b>").append(esc(attribute)).append("</b>")
            if (type != null && !type.names.any { it.equals(attribute, true) }) append(" (").append(esc(type.nameOrOID)).append(")")
            type?.description?.takeIf { it.isNotBlank() }?.let { append("<br>").append(esc(it)) }
            if (type != null) {
                val syntax = schema.syntaxOid(attribute)
                val syntaxName = syntax?.let { schema.schema?.getAttributeSyntax(it)?.description ?: SchemaIndex.KNOWN_SYNTAXES[it] ?: it }
                append("<br>")
                syntaxName?.let { append(esc(it)).append(" · ") }
                append(if (type.isSingleValued) "single-valued" else "multi-valued")
            }
            append("<br>")
            append(
                when {
                    operational -> "Operational attribute (read-only)"
                    schema.schema != null && type == null -> "<font color='#${ColorUtil.toHex(JBColor.RED)}'>Not defined in the schema</font>"
                    !allowed -> "<font color='#${ColorUtil.toHex(JBColor.RED)}'>Not allowed by the object classes of this entry</font>"
                    model.isMust(attribute) -> "Required by the object classes"
                    schema.schema != null -> "Optional"
                    else -> ""
                },
            )
            append("</html>")
        }
    }

    // ------------------------------------------------------------------ FileEditor

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = table
    override fun getName() = "LDAP Entry"
    override fun setState(state: FileEditorState) {}
    override fun isModified() = model.isModified
    override fun isValid() = true
    override fun getFile(): VirtualFile = file
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = changeSupport.addPropertyChangeListener(listener)
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = changeSupport.removePropertyChangeListener(listener)
    override fun dispose() {}

    private fun isNavigationClick(e: MouseEvent) = if (com.intellij.openapi.util.SystemInfo.isMac) e.isMetaDown else e.isControlDown

    private companion object {
        val GROUP_STRIPE: JBColor = JBColor.namedColor("Table.stripeColor", JBColor(0xF7F8FA, 0x2B2D30))
    }
}
