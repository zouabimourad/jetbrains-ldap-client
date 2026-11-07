package com.mouradzouabi.ldapclient.ui.search

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.TextFieldWithStoredHistory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.Filter
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.SearchScope
import com.mouradzouabi.ldapclient.model.LdapConnectionConfig
import com.mouradzouabi.ldapclient.model.LdapSettings
import com.mouradzouabi.ldapclient.model.SavedSearch
import com.mouradzouabi.ldapclient.service.LdapConnectionManager
import com.mouradzouabi.ldapclient.service.LdapProjectService
import com.mouradzouabi.ldapclient.ui.UiDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.swing.JComponent

data class SearchParams(
    val configId: String,
    val connectionName: String,
    val baseDn: String,
    val scope: SearchScope,
    val filter: String,
    val attributes: List<String>,
    val sizeLimit: Int,
    val includeOperational: Boolean,
) {
    companion object {
        fun parseAttributes(text: String, includeOperational: Boolean): List<String> {
            val attributes = text.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (attributes.isEmpty()) attributes += "*"
            if (includeOperational && "+" !in attributes) attributes += "+"
            return attributes
        }

        fun scopeName(scope: SearchScope) = when (scope) {
            SearchScope.ONE -> "ONE"
            SearchScope.BASE -> "BASE"
            else -> "SUB"
        }

        fun scopeOf(name: String): SearchScope = when (name) {
            "ONE" -> SearchScope.ONE
            "BASE" -> SearchScope.BASE
            else -> SearchScope.SUB
        }

        /** Parameters of a saved search, or null when its connection no longer exists. */
        fun of(saved: SavedSearch): SearchParams? {
            val config = LdapSettings.getInstance().find(saved.configId) ?: return null
            return SearchParams(
                config.id, config.displayName, saved.baseDn, scopeOf(saved.scope), saved.filter,
                parseAttributes(saved.attributes, saved.includeOperational), saved.sizeLimit, saved.includeOperational,
            )
        }
    }
}

class SearchDialog(private val project: Project, initialConfigId: String?, initialBaseDn: String?) : DialogWrapper(project, true) {
    private val settings = LdapSettings.getInstance()
    private val scope = LdapProjectService.getInstance(project).childScope(disposable)
    private var schemaJob: Job? = null

    private val savedCombo = ComboBox<Any>().apply {
        renderer = SimpleListCellRenderer.create("") { (it as? SavedSearch)?.name ?: "<none>" }
    }
    private val connectionCombo = ComboBox(settings.connections.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.displayName }
        settings.connections.firstOrNull { it.id == initialConfigId }?.let { c ->
            selectedIndex = (0 until itemCount).first { getItemAt(it).id == c.id }
        }
    }
    private val baseDnField = JBTextField(initialBaseDn ?: "")
    private val scopeCombo = ComboBox(arrayOf("Subtree", "One level", "Base object"))
    private val filterField = LdapFilterField(project, settings.recentFilters.firstOrNull() ?: "(objectClass=*)")
    private val attributesField = TextFieldWithStoredHistory("ldap.client.search.attributes").apply {
        if (text.isNullOrBlank()) text = "cn, objectClass"
    }
    private val sizeLimitSpinner = JBIntSpinner(settings.defaultSearchSizeLimit, 0, 10_000_000)
    private val operationalBox = JBCheckBox("Include operational attributes", settings.showOperationalAttributes)

    private val recentAction = object : DumbAwareAction("Recent Filters", "Choose a recently used filter", AllIcons.Actions.SearchWithHistory) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = settings.recentFilters.isNotEmpty()
        }

        override fun actionPerformed(e: AnActionEvent) {
            JBPopupFactory.getInstance().createPopupChooserBuilder(settings.recentFilters)
                .setItemChosenCallback { filterField.text = it }
                .createPopup()
                .showUnderneathOf(filterField)
        }
    }
    private val saveAction = object : DumbAwareAction("Save Search…", "Save these parameters as a named search", AllIcons.Actions.MenuSaveall) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = saveSearch()
    }
    private val deleteAction = object : DumbAwareAction("Delete Saved Search", null, AllIcons.General.Remove) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = savedCombo.selectedItem is SavedSearch
        }

        override fun actionPerformed(e: AnActionEvent) {
            val saved = savedCombo.selectedItem as? SavedSearch ?: return
            settings.removeSearch(saved.name)
            reloadSavedSearches(null)
        }
    }

    init {
        title = "LDAP Search"
        reloadSavedSearches(null)
        savedCombo.addActionListener { (savedCombo.selectedItem as? SavedSearch)?.let(::apply) }
        connectionCombo.addActionListener { loadSchema() }
        init()
        loadSchema()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Saved search:") {
            cell(savedCombo).align(AlignX.FILL).resizableColumn()
            cell(iconButton(saveAction))
            cell(iconButton(deleteAction))
        }
        separator()
        row("Connection:") { cell(connectionCombo).align(AlignX.FILL) }
        row("Base DN:") { cell(baseDnField).align(AlignX.FILL).comment("Empty: first base DN of the connection") }
        row("Scope:") { cell(scopeCombo) }
        row("Filter:") {
            cell(filterField).align(AlignX.FILL).resizableColumn().focused()
                .comment("RFC 4515, e.g. (&amp;(objectClass=person)(|(cn=john*)(mail=*@example.com))). Ctrl+Space completes attribute and object class names")
            cell(iconButton(recentAction))
        }
        row("Attributes:") {
            cell(attributesField).align(AlignX.FILL).comment("Comma separated; * = all user attributes, + = operational")
        }
        row("Size limit:") { cell(sizeLimitSpinner).comment("Maximum entries to fetch, 0 = unlimited (results are streamed page by page)") }
        row { cell(operationalBox) }
    }

    override fun getPreferredFocusedComponent(): JComponent = filterField

    private fun iconButton(action: com.intellij.openapi.actionSystem.AnAction) = com.intellij.openapi.actionSystem.impl.ActionButton(
        action, action.templatePresentation.clone(), "LdapSearchDialog", com.intellij.openapi.actionSystem.ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE,
    )

    private fun reloadSavedSearches(select: String?) {
        val items = listOf<Any>("") + settings.savedSearches
        savedCombo.model = javax.swing.DefaultComboBoxModel(items.toTypedArray())
        savedCombo.selectedItem = items.firstOrNull { (it as? SavedSearch)?.name == select } ?: ""
    }

    private fun apply(saved: SavedSearch) {
        (0 until connectionCombo.itemCount).firstOrNull { connectionCombo.getItemAt(it).id == saved.configId }
            ?.let { connectionCombo.selectedIndex = it }
        baseDnField.text = saved.baseDn
        scopeCombo.selectedIndex = when (saved.scope) {
            "ONE" -> 1
            "BASE" -> 2
            else -> 0
        }
        filterField.text = saved.filter
        attributesField.text = saved.attributes
        sizeLimitSpinner.number = saved.sizeLimit
        operationalBox.isSelected = saved.includeOperational
    }

    private fun saveSearch() {
        doValidate()?.let {
            Messages.showErrorDialog(contentPanel, it.message, "Save Search")
            return
        }
        val current = savedCombo.selectedItem as? SavedSearch
        val name = Messages.showInputDialog(contentPanel, "Name:", "Save Search", null, current?.name ?: filterField.text.trim(), null)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val p = params
        settings.saveSearch(SavedSearch().apply {
            this.name = name
            configId = p.configId
            baseDn = p.baseDn
            scope = SearchParams.scopeName(p.scope)
            filter = p.filter
            attributes = attributesField.text.trim()
            sizeLimit = p.sizeLimit
            includeOperational = p.includeOperational
        })
        reloadSavedSearches(name)
    }

    /** Loads the schema of the selected connection in the background to drive filter completion. */
    private fun loadSchema() {
        val config = connectionCombo.selectedItem as? LdapConnectionConfig ?: return
        schemaJob?.cancel()
        schemaJob = scope.launch(UiDispatcher) {
            runCatching { LdapConnectionManager.getInstance().connect(config.id).schemaAsync() }
                .onSuccess { filterField.schema = it }
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (connectionCombo.selectedItem == null) return ValidationInfo("Select a connection", connectionCombo)
        val base = baseDnField.text.trim()
        if (base.isNotEmpty() && !DN.isValidDN(base)) return ValidationInfo("Invalid base DN", baseDnField)
        return try {
            Filter.create(filterField.text.trim())
            null
        } catch (e: LDAPException) {
            ValidationInfo("Invalid filter: ${e.exceptionMessage}", filterField)
        }
    }

    override fun doOKAction() {
        settings.addRecentFilter(filterField.text.trim())
        attributesField.addCurrentTextToHistory()
        settings.defaultSearchSizeLimit = sizeLimitSpinner.number
        super.doOKAction()
    }

    val params: SearchParams
        get() {
            val config = connectionCombo.selectedItem as LdapConnectionConfig
            return SearchParams(
                configId = config.id,
                connectionName = config.displayName,
                baseDn = baseDnField.text.trim(),
                scope = when (scopeCombo.selectedIndex) {
                    1 -> SearchScope.ONE
                    2 -> SearchScope.BASE
                    else -> SearchScope.SUB
                },
                filter = filterField.text.trim(),
                attributes = SearchParams.parseAttributes(attributesField.text, operationalBox.isSelected),
                sizeLimit = sizeLimitSpinner.number,
                includeOperational = operationalBox.isSelected,
            )
        }
}
