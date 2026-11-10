package com.mouradzouabi.ldapclient.ui.schema

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mouradzouabi.ldapclient.core.LdapErrors
import com.mouradzouabi.ldapclient.core.SchemaIndex
import com.mouradzouabi.ldapclient.core.SchemaItem
import com.mouradzouabi.ldapclient.core.SchemaKind
import com.mouradzouabi.ldapclient.service.LdapConnectionManager
import com.mouradzouabi.ldapclient.service.LdapProjectService
import com.mouradzouabi.ldapclient.ui.UiDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.HyperlinkEvent
import kotlin.coroutines.cancellation.CancellationException

/**
 * Browses the schema of a connection: object classes, attribute types, matching rules and syntaxes,
 * each with a filterable list and cross-linked details ("used by", superiors, subclasses…).
 */
class LdapSchemaEditor(private val project: Project, private val file: LdapSchemaVirtualFile) : UserDataHolderBase(), FileEditor {
    private val scope = LdapProjectService.getInstance(project).childScope(this)
    private val pages = SchemaKind.entries.associateWith { Page(it) }
    private val tabs = JBTabbedPane()
    private val status = JBLabel()
    private val panel = JPanel(BorderLayout())
    private val history = ArrayDeque<Pair<SchemaKind, String>>()
    private var index: SchemaIndex? = null
    private var pending: Pair<SchemaKind, String>? = file.requestedSelection.also { file.requestedSelection = null }
    private var loadJob: Job? = null

    init {
        pages.values.forEach { tabs.addTab(it.kind.title, it) }
        val actions = DefaultActionGroup().apply {
            add(object : DumbAwareAction("Back", "Go back to the previous element", AllIcons.Actions.Back) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = history.size > 1
                }

                override fun actionPerformed(e: AnActionEvent) {
                    history.removeLastOrNull()
                    history.removeLastOrNull()?.let { (kind, oid) -> select(kind, oid) }
                }
            })
            add(object : DumbAwareAction("Reload Schema", "Read the schema from the server again", AllIcons.Actions.Refresh) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = loadJob?.isActive != true
                }

                override fun actionPerformed(e: AnActionEvent) = load(reload = true)
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("LdapSchemaBrowser", actions, true)
        toolbar.targetComponent = tabs
        val top = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(status.apply { border = JBUI.Borders.emptyLeft(8) }, BorderLayout.CENTER)
        }
        panel.add(top, BorderLayout.NORTH)
        panel.add(tabs, BorderLayout.CENTER)
        load(reload = false)
    }

    private fun load(reload: Boolean) {
        loadJob?.cancel()
        status.icon = AnimatedIcon.Default.INSTANCE
        status.text = "Loading schema…"
        loadJob = scope.launch(UiDispatcher) {
            try {
                val session = LdapConnectionManager.getInstance().connect(file.configId)
                if (reload) session.invalidateSchema()
                val schema = session.schemaAsync().schema
                if (schema == null) {
                    status.icon = AllIcons.General.Warning
                    status.text = "The server did not return a readable schema"
                    return@launch
                }
                val loaded = withContext(Dispatchers.Default) { SchemaIndex(schema) }
                index = loaded
                pages.values.forEach { it.setItems(loaded.items(it.kind)) }
                status.icon = null
                status.text = "${loaded.objectClasses.size} object classes, ${loaded.attributeTypes.size} attribute types, " +
                    "${loaded.matchingRules.size} matching rules, ${loaded.syntaxes.size} syntaxes"
                pending?.let { (kind, name) -> select(kind, name) }
                pending = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.icon = AllIcons.General.Error
                status.text = LdapErrors.describe(e)
            }
        }
    }

    /** Shows the element [name] (name or OID) of [kind]. */
    fun select(kind: SchemaKind, name: String) {
        val loaded = index
        if (loaded == null) {
            pending = kind to name
            return
        }
        val item = loaded.find(kind, name) ?: return
        tabs.selectedIndex = kind.ordinal
        pages.getValue(kind).select(item)
    }

    private fun recordHistory(item: SchemaItem) {
        val key = item.kind to item.oid
        if (history.lastOrNull() != key) history.addLast(key)
        while (history.size > 50) history.removeFirst()
    }

    private inner class Page(val kind: SchemaKind) : JPanel(BorderLayout()) {
        private var all: List<SchemaItem> = emptyList()
        private val model = CollectionListModel<SchemaItem>()
        private val list = JBList(model)
        private val filter = SearchTextField(false)
        private val details = JEditorPane().apply {
            editorKit = HTMLEditorKitBuilder().build()
            isEditable = false
            border = JBUI.Borders.empty(8, 12)
            addHyperlinkListener { e ->
                if (e.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
                val target = e.description ?: return@addHyperlinkListener
                val linkKind = SchemaKind.ofPrefix(target.substringBefore(':')) ?: return@addHyperlinkListener
                select(linkKind, target.substringAfter(':'))
            }
        }

        init {
            list.selectionMode = ListSelectionModel.SINGLE_SELECTION
            list.cellRenderer = object : ColoredListCellRenderer<SchemaItem>() {
                override fun customizeCellRenderer(list: JList<out SchemaItem>, value: SchemaItem?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    value ?: return
                    icon = when (kind) {
                        SchemaKind.OBJECT_CLASS -> AllIcons.Nodes.Class
                        SchemaKind.ATTRIBUTE_TYPE -> AllIcons.Nodes.Field
                        SchemaKind.MATCHING_RULE -> AllIcons.Nodes.Function
                        SchemaKind.SYNTAX -> AllIcons.Nodes.Type
                    }
                    append(value.label, if (value.obsolete) SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, null) else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    if (value.label != value.oid) append("  ${value.oid}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
            list.emptyText.text = "Loading…"
            list.addListSelectionListener {
                if (!it.valueIsAdjusting) list.selectedValue?.let(::show)
            }
            filter.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = applyFilter()
            })
            val left = JPanel(BorderLayout()).apply {
                add(filter.apply { border = JBUI.Borders.empty(4) }, BorderLayout.NORTH)
                add(ScrollPaneFactory.createScrollPane(list, true), BorderLayout.CENTER)
            }
            val splitter = OnePixelSplitter(false, 0.3f)
            splitter.firstComponent = left
            splitter.secondComponent = ScrollPaneFactory.createScrollPane(details, true)
            add(splitter, BorderLayout.CENTER)
        }

        fun setItems(items: List<SchemaItem>) {
            all = items
            list.emptyText.text = "No ${kind.title.lowercase()}"
            applyFilter()
            if (list.selectedIndex < 0 && model.size > 0) list.selectedIndex = 0
        }

        private fun applyFilter() {
            val selected = list.selectedValue
            val text = filter.text.trim()
            model.replaceAll(all.filter { it.matches(text) })
            if (selected != null) list.setSelectedValue(selected, true)
        }

        fun select(item: SchemaItem) {
            if (!item.matches(filter.text.trim())) filter.text = ""
            list.setSelectedValue(item, true)
            show(item)
        }

        private fun show(item: SchemaItem) {
            val loaded = index ?: return
            recordHistory(item)
            details.text = SchemaHtml.render(loaded, item)
            details.caretPosition = 0
        }
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = tabs
    override fun getName() = "LDAP Schema"
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun getFile(): VirtualFile = file
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {}
}

/** HTML details of a schema element; references are `kind:oid` links. */
object SchemaHtml {
    fun render(index: SchemaIndex, item: SchemaItem): String = buildString {
        append("<html><body>")
        append("<h2>").append(esc(item.label)).append("</h2>")
        append("<table cellpadding='2' width='100%'>")
        row("OID", esc(item.oid))
        if (item.names.size > 1) row("Names", item.names.joinToString(", ") { esc(it) })
        item.description?.takeIf { it.isNotBlank() && it != item.label }?.let { row("Description", esc(it)) }
        if (item.obsolete) row("Obsolete", "yes")
        when (item.kind) {
            SchemaKind.OBJECT_CLASS -> objectClass(index, item)
            SchemaKind.ATTRIBUTE_TYPE -> attributeType(index, item)
            SchemaKind.MATCHING_RULE -> {
                index.schema.getMatchingRule(item.oid)?.syntaxOID?.let { row("Syntax", link(index, SchemaKind.SYNTAX, it)) }
                row("Used by", links(index, SchemaKind.ATTRIBUTE_TYPE, index.attributesWithMatchingRule(item.oid)))
            }
            SchemaKind.SYNTAX -> row("Used by", links(index, SchemaKind.ATTRIBUTE_TYPE, index.attributesWithSyntax(item.oid)))
        }
        append("</table>")
        definition(index, item)?.let {
            append("<h3>Definition</h3><p style='font-family: Monospaced'>").append(esc(it)).append("</p>")
        }
        append("</body></html>")
    }

    private fun StringBuilder.objectClass(index: SchemaIndex, item: SchemaItem) {
        val def = index.objectClass(item.oid) ?: return
        row("Type", def.getObjectClassType(index.schema)?.name?.lowercase() ?: "structural")
        row("Superior classes", links(index, SchemaKind.OBJECT_CLASS, def.superiorClasses.toList()))
        fun own(names: Array<String>) = names.mapNotNull { index.schema.getAttributeType(it)?.oid?.lowercase() }.toSet()
        row("Required (MUST)", attributeLinks(def.getRequiredAttributes(index.schema, true), own(def.requiredAttributes)))
        row("Optional (MAY)", attributeLinks(def.getOptionalAttributes(index.schema, true), own(def.optionalAttributes)))
        row("Subclasses", links(index, SchemaKind.OBJECT_CLASS, index.subclasses(item.oid)))
        if (def.superiorClasses.isNotEmpty()) row("", "<span style='color: ${grey()}'>Inherited attributes are grayed out</span>")
    }

    private fun StringBuilder.attributeType(index: SchemaIndex, item: SchemaItem) {
        val def = index.attributeType(item.oid) ?: return
        def.superiorType?.let { row("Superior type", link(index, SchemaKind.ATTRIBUTE_TYPE, it)) }
        index.syntaxOid(def)?.let { row("Syntax", link(index, SchemaKind.SYNTAX, it)) }
        row("Single-valued", if (def.isSingleValued) "yes" else "no")
        row("Usage", def.usage?.getName() ?: "userApplications")
        if (def.isNoUserModification) row("User modification", "not allowed")
        if (def.isCollective) row("Collective", "yes")
        def.getEqualityMatchingRule(index.schema)?.let { row("Equality", link(index, SchemaKind.MATCHING_RULE, it)) }
        def.getOrderingMatchingRule(index.schema)?.let { row("Ordering", link(index, SchemaKind.MATCHING_RULE, it)) }
        def.getSubstringMatchingRule(index.schema)?.let { row("Substring", link(index, SchemaKind.MATCHING_RULE, it)) }
        row("Required by", links(index, SchemaKind.OBJECT_CLASS, index.requiredIn(item.oid)))
        row("Optional in", links(index, SchemaKind.OBJECT_CLASS, index.optionalIn(item.oid)))
        index.subtypes(item.oid).takeIf { it.isNotEmpty() }?.let { row("Subtypes", links(index, SchemaKind.ATTRIBUTE_TYPE, it)) }
    }

    private fun definition(index: SchemaIndex, item: SchemaItem): String? = when (item.kind) {
        SchemaKind.OBJECT_CLASS -> index.schema.getObjectClass(item.oid)?.toString()
        SchemaKind.ATTRIBUTE_TYPE -> index.schema.getAttributeType(item.oid)?.toString()
        SchemaKind.MATCHING_RULE -> index.schema.getMatchingRule(item.oid)?.toString()
        SchemaKind.SYNTAX -> index.schema.getAttributeSyntax(item.oid)?.toString()
    }

    private fun StringBuilder.row(label: String, value: String) {
        append("<tr><td valign='top' nowrap style='color: ").append(grey()).append("; padding-right: 12px'>").append(esc(label))
            .append("</td><td valign='top'>").append(value.ifEmpty { "<i>none</i>" }).append("</td></tr>")
    }

    private fun attributeLinks(types: Collection<com.unboundid.ldap.sdk.schema.AttributeTypeDefinition>, own: Set<String>): String =
        types.sortedBy { it.nameOrOID.lowercase() }.joinToString(", ") { type ->
            val link = "<a href='${SchemaKind.ATTRIBUTE_TYPE.linkPrefix}:${esc(type.oid)}'>${esc(type.nameOrOID)}</a>"
            if (type.oid.lowercase() in own) link else "<span style='color: ${grey()}'>$link</span>"
        }

    private fun links(index: SchemaIndex, kind: SchemaKind, names: List<String>) = names.joinToString(", ") { link(index, kind, it) }

    private fun link(index: SchemaIndex, kind: SchemaKind, nameOrOid: String): String {
        val target = index.find(kind, nameOrOid) ?: return esc(nameOrOid)
        return "<a href='${kind.linkPrefix}:${esc(target.oid)}'>${esc(target.label)}</a>"
    }

    private fun grey() = "#" + UIUtil.colorToHex(UIUtil.getContextHelpForeground())

    private fun esc(text: String) = StringUtil.escapeXmlEntities(text)
}
