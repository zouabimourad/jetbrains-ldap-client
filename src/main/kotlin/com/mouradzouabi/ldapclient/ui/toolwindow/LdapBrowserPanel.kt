package com.mouradzouabi.ldapclient.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.unboundid.ldap.sdk.Filter
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.SearchResultEntry
import com.unboundid.ldap.sdk.SearchScope
import com.mouradzouabi.ldapclient.core.AccountTools
import com.mouradzouabi.ldapclient.core.LdapDn
import com.mouradzouabi.ldapclient.core.LdapErrors
import com.mouradzouabi.ldapclient.core.LdapSession
import com.mouradzouabi.ldapclient.core.PagedSearch
import com.mouradzouabi.ldapclient.model.LdapConnectionConfig
import com.mouradzouabi.ldapclient.model.LdapCredentials
import com.mouradzouabi.ldapclient.model.BulkChanges
import com.mouradzouabi.ldapclient.model.LdapListener
import com.mouradzouabi.ldapclient.model.LdapSettings
import com.mouradzouabi.ldapclient.service.LdapConnectionManager
import com.mouradzouabi.ldapclient.service.LdapNotifications
import com.mouradzouabi.ldapclient.service.LdapProjectService
import com.mouradzouabi.ldapclient.ui.EntryOperations
import com.mouradzouabi.ldapclient.ui.EntryTools
import com.mouradzouabi.ldapclient.ui.log.OperationLogPanel
import com.mouradzouabi.ldapclient.ui.schema.LdapSchemaVirtualFile
import com.mouradzouabi.ldapclient.ui.UiDispatcher
import com.mouradzouabi.ldapclient.ui.connection.ConnectionDialog
import com.mouradzouabi.ldapclient.ui.search.SearchDialog
import com.mouradzouabi.ldapclient.ui.search.SearchParams
import com.mouradzouabi.ldapclient.ui.search.SearchResultsPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.DropMode
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.TransferHandler
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.MutableTreeNode
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel
import kotlin.coroutines.cancellation.CancellationException

/**
 * LDAP directory browser. Children are loaded lazily, page by page, in coroutines; the EDT only
 * applies the resulting (already built) nodes to the tree model.
 */
class LdapBrowserPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val scope = LdapProjectService.getInstance(project).childScope(this)
    private val manager = LdapConnectionManager.getInstance()
    private val settings = LdapSettings.getInstance()
    private val root = RootNode()
    private val model = DefaultTreeModel(root)
    val tree = Tree(model)

    private val writable: () -> Boolean = {
        selectedEntry != null && isConnected(selectedConnection) && selectedConnection?.config?.readOnly == false
    }

    private val addConnectionAction = action("Add Connection…", AllIcons.General.Add) { addConnection() }
    private val editConnectionAction = action("Edit Connection…", AllIcons.Actions.Edit, { selectedConnection != null }) {
        selectedConnection?.let(::editConnection)
    }
    private val removeConnectionAction = action("Remove Connection", AllIcons.General.Remove, { selectedNode is ConnectionNode }) {
        (selectedNode as? ConnectionNode)?.let(::removeConnection)
    }
    private val moveUpAction = action("Move Up", AllIcons.Actions.MoveUp, { connectionIndex(selectedNode) > 0 }, { selectedNode is ConnectionNode }) {
        moveSelectedConnection(-1)
    }
    private val moveDownAction = action("Move Down", AllIcons.Actions.MoveDown,
        { connectionIndex(selectedNode).let { it >= 0 && it < root.childCount - 1 } }, { selectedNode is ConnectionNode }) {
        moveSelectedConnection(1)
    }
    private val connectAction = action("Connect", AllIcons.Actions.Execute, {
        selectedConnection?.let { !manager.isConnected(it.configId) && !manager.isConnecting(it.configId) } == true
    }) {
        selectedConnection?.let {
            connect(it)
            tree.expandPath(TreePath(it.path))
        }
    }
    private val disconnectAction = action("Disconnect", AllIcons.Actions.Suspend, { isConnected(selectedConnection) }) {
        selectedConnection?.let { manager.disconnect(it.configId) }
    }
    private val refreshAction = action("Refresh", AllIcons.Actions.Refresh, { selectedNode != null && isConnected(selectedConnection) }) {
        selectedNode?.let(::refresh)
    }
    private val openEntryAction = action("Open Entry", AllIcons.Actions.EditSource, { selectedEntry != null }) {
        selectedEntry?.let { EntryOperations.open(project, it.configId, it.dn) }
    }
    private val loadMoreAction = action("Load More", AllIcons.General.ArrowDown, visible = { selectedNode is LoadMoreNode }) {
        (selectedNode as? LoadMoreNode)?.let { loadChildren(it.owner, reset = false) }
    }
    private val filterAction = action("Filter Children…", AllIcons.General.Filter, { selectedEntry != null && isConnected(selectedConnection) }) {
        selectedEntry?.let(::filterChildren)
    }
    private val clearFilterAction = action("Clear Filter", null, visible = { selectedEntry?.childFilter != null }) {
        selectedEntry?.let { setChildFilter(it, null) }
    }
    private val searchAction = action("Search…", AllIcons.Actions.Find, { selectedConnection != null || root.childCount > 0 }) {
        openSearch(selectedEntry?.dn, selectedConnection?.configId)
    }
    private val importLdifAction = action("Import / Run LDIF File…", AllIcons.ToolbarDecorator.Import, { root.childCount > 0 }) {
        com.mouradzouabi.ldapclient.ui.ldif.LdifRunner.importFile(project, selectedConnection?.configId)
    }
    private val showGroupsAction = action("Show Groups…", AllIcons.Nodes.Related, {
        selectedEntry?.let { it.dn.isNotEmpty() && isConnected(selectedConnection) } == true
    }) {
        selectedEntry?.let { com.mouradzouabi.ldapclient.ui.membership.MembershipNavigator.showGroups(project, it.configId, it.dn, tree) }
    }
    private val showMembersAction = action("Show Members…", AllIcons.General.User, {
        selectedEntry?.let { com.mouradzouabi.ldapclient.core.GroupMembership.isGroup(it.objectClasses) && isConnected(selectedConnection) } == true
    }) {
        selectedEntry?.let { com.mouradzouabi.ldapclient.ui.membership.MembershipNavigator.showMembers(project, it.configId, it.dn, tree) }
    }
    private val exportLdifAction = action("Export LDIF…", AllIcons.ToolbarDecorator.Export, { selectedEntry != null && isConnected(selectedConnection) }) {
        selectedEntry?.let { com.mouradzouabi.ldapclient.ui.ldif.LdifExporter.export(project, it.configId, it.dn) }
    }
    private val newEntryAction = action("New Child Entry…", AllIcons.Actions.New, writable) {
        selectedEntry?.let { EntryOperations.newChild(project, it.configId, it.dn) }
    }
    private val renameAction = action("Rename…", AllIcons.Actions.Edit, { writable() && selectedEntry?.isBase == false }) {
        selectedEntry?.let { EntryOperations.rename(project, it.configId, it.dn) }
    }
    private val deleteAction = action("Delete…", AllIcons.General.Delete, writable) {
        selectedEntry?.let { EntryOperations.delete(project, it.configId, it.dn) }
    }
    private val copyDnAction = action("Copy DN", AllIcons.Actions.Copy, { selectedEntry != null }) {
        selectedEntry?.let { EntryOperations.copyDn(it.dn) }
    }
    private val duplicateAction = action("Duplicate Entry…", AllIcons.Actions.Copy, { writable() && selectedEntry?.dn?.isNotEmpty() == true }) {
        selectedEntry?.let { EntryTools.duplicate(project, it.configId, it.dn) }
    }
    private val copyMoveAction = action("Copy / Move…", AllIcons.Actions.Forward, {
        selectedEntry?.dn?.isNotEmpty() == true && isConnected(selectedConnection)
    }) {
        selectedEntry?.let { EntryTools.copyOrMove(project, it.configId, it.dn, move = false) }
    }
    private val compareAction = action("Compare With…", AllIcons.Actions.Diff, { selectedEntry?.dn?.isNotEmpty() == true }) {
        selectedEntry?.let { EntryTools.compareWith(project, it.configId, it.dn) }
    }
    private val accountStatusAction = action("Account Status…", AllIcons.Nodes.Padlock,
        enabled = { isConnected(selectedConnection) },
        visible = { selectedEntry?.let { AccountTools.isAccount(it.objectClasses) } == true },
    ) {
        selectedEntry?.let { EntryTools.accountStatus(project, it.configId, it.dn) }
    }
    private val schemaAction = action("Schema Browser", AllIcons.Nodes.DataSchema, { selectedConnection != null }) {
        selectedConnection?.let { LdapSchemaVirtualFile.open(project, it.configId) }
    }
    private val operationLogAction = action("Operation Log", AllIcons.Vcs.History) { OperationLogPanel.show(project) }

    /** Dropdown listing the saved searches; each one runs directly. */
    private val savedSearchesGroup = object : ActionGroup("Saved Searches", "Run a saved search", AllIcons.Nodes.Favorite), DumbAware {
        init {
            templatePresentation.isPopupGroup = true
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = settings.savedSearches.isNotEmpty()
        }

        override fun getChildren(e: AnActionEvent?): Array<AnAction> = settings.savedSearches.map { saved ->
            val connection = settings.find(saved.configId)?.displayName ?: "unknown connection"
            object : DumbAwareAction(saved.name, "$connection: ${saved.filter}", null) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) {
                    val params = SearchParams.of(saved)
                    if (params == null) LdapNotifications.error(project, "Saved search", "The connection of '${saved.name}' no longer exists")
                    else SearchResultsPanel.show(project, params)
                }
            }
        }.toTypedArray()
    }

    private val operationalToggle = object : ToggleAction("Show Operational Attributes", "Show operational attributes in entry editors", AllIcons.Actions.ToggleVisibility) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = settings.showOperationalAttributes
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            settings.showOperationalAttributes = state
        }
    }

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = LdapTreeCellRenderer()
        javax.swing.ToolTipManager.sharedInstance().registerComponent(tree)
        tree.putClientProperty(AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)
        tree.toggleClickCount = 0
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.emptyText.text = "No LDAP connections"
        tree.emptyText.appendSecondaryText("Add connection…", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { addConnection() }
        TreeSpeedSearch.installOn(tree, false) { path -> nodeText(path.lastPathComponent) }

        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                when (val node = event.path.lastPathComponent) {
                    is ConnectionNode -> if (!node.populated) connect(node)
                    is EntryNode -> if (node.loadState == LoadState.NOT_LOADED) loadChildren(node, reset = false)
                }
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) {}
        })
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val path = tree.getPathForLocation(event.x, event.y) ?: return false
                activate(path.lastPathComponent as? LdapTreeNode ?: return false)
                return true
            }
        }.installOn(tree)
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && e.modifiersEx == 0) {
                    selectedNode?.let { activate(it); e.consume() }
                }
            }
        })

        val toolbar = ActionManager.getInstance().createActionToolbar("LdapBrowserToolbar", createToolbarGroup(), true)
        toolbar.targetComponent = tree
        setToolbar(toolbar.component)
        setContent(ScrollPaneFactory.createScrollPane(tree, true))
        PopupHandler.installPopupMenu(tree, createPopupGroup(), "LdapBrowserPopup")
        val actionManager = ActionManager.getInstance()
        moveUpAction.registerCustomShortcutSet(actionManager.getAction("MoveStatementUp")?.shortcutSet ?: CustomShortcutSet.EMPTY, tree)
        moveDownAction.registerCustomShortcutSet(actionManager.getAction("MoveStatementDown")?.shortcutSet ?: CustomShortcutSet.EMPTY, tree)
        tree.transferHandler = ConnectionTransferHandler(tree.transferHandler)
        tree.dragEnabled = true
        tree.dropMode = DropMode.ON

        ApplicationManager.getApplication().messageBus.connect(this).subscribe(LdapListener.TOPIC, object : LdapListener {
            override fun connectionsChanged() = syncConnections()
            override fun connectionStateChanged(configId: String) = onConnectionStateChanged(configId)
            override fun childrenChanged(configId: String, parentDn: String) = onChildrenChanged(configId, parentDn)
            override fun entryDeleted(configId: String, dn: String) = onEntryDeleted(configId, dn)
            override fun entriesChanged(configId: String, changes: BulkChanges) = onEntriesChanged(configId, changes)
        })
        syncConnections()
    }

    // ---------------------------------------------------------------- selection helpers

    private val selectedNode: LdapTreeNode? get() = tree.selectionPath?.lastPathComponent as? LdapTreeNode

    private val selectedConnection: ConnectionNode?
        get() = selectedNode?.path?.firstNotNullOfOrNull { it as? ConnectionNode }

    private val selectedEntry: EntryNode? get() = selectedNode as? EntryNode

    private fun isConnected(node: ConnectionNode?) = node != null && manager.isConnected(node.configId)

    private fun nodeText(node: Any?): String = when (node) {
        is ConnectionNode -> node.config.displayName
        is EntryNode -> node.label
        else -> ""
    }

    private fun activate(node: LdapTreeNode) {
        when (node) {
            is ConnectionNode -> {
                val path = TreePath(node.path)
                if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
            }
            is EntryNode -> EntryOperations.open(project, node.configId, node.dn)
            is LoadMoreNode -> loadChildren(node.owner, reset = false)
            is StatusNode -> (node.parent as? EntryNode)?.let { if (node.kind == StatusNode.Kind.ERROR) loadChildren(it, reset = true) }
        }
    }

    // ---------------------------------------------------------------- connections

    private fun syncConnections() {
        val configs = settings.connections
        val existing = root.children().toList().filterIsInstance<ConnectionNode>().associateBy { it.configId }
        for (node in existing.values) {
            if (configs.none { it.id == node.configId }) {
                discardCursors(node)
                model.removeNodeFromParent(node)
            }
        }
        configs.forEachIndexed { index, config ->
            val node = existing[config.id]
            if (node == null) {
                val created = ConnectionNode(config).apply { add(StatusNode("Connecting…", StatusNode.Kind.LOADING)) }
                model.insertNodeInto(created, root, minOf(index, root.childCount))
            } else {
                node.config = config
                model.nodeChanged(node)
            }
        }
        reorderConnections(configs.map { it.id })
        if (root.childCount > 0 && tree.selectionPath == null) tree.expandPath(TreePath(root))
    }

    /** Moves connection nodes to match [ids], keeping their expanded subtrees and the selection. */
    private fun reorderConnections(ids: List<String>) {
        ids.forEachIndexed { index, id ->
            val node = connectionNode(id) ?: return@forEachIndexed
            if (root.getIndex(node) == index) return@forEachIndexed
            val path = TreePath(node.path)
            val expanded = tree.getExpandedDescendants(path)?.toList().orEmpty()
            val selected = tree.selectionPath?.takeIf { path.isDescendant(it) }
            model.removeNodeFromParent(node)
            model.insertNodeInto(node, root, minOf(index, root.childCount))
            expanded.forEach(tree::expandPath)
            selected?.let {
                tree.selectionPath = it
                tree.scrollPathToVisible(it)
            }
        }
    }

    private fun connectionIndex(node: LdapTreeNode?): Int = if (node is ConnectionNode && node.parent === root) root.getIndex(node) else -1

    private fun moveSelectedConnection(delta: Int) {
        val node = selectedNode as? ConnectionNode ?: return
        val index = connectionIndex(node)
        if (index >= 0) settings.moveConnection(node.configId, index + delta)
    }

    /** Drag a connection onto another one to take its place. Other nodes are not draggable. */
    private inner class ConnectionTransferHandler(private val delegate: TransferHandler?) : TransferHandler() {
        private var draggedId: String? = null

        override fun getSourceActions(c: JComponent): Int = if (selectedNode is ConnectionNode) MOVE else NONE

        override fun createTransferable(c: JComponent): Transferable? {
            val id = (selectedNode as? ConnectionNode)?.configId ?: return null
            draggedId = id
            return object : Transferable {
                override fun getTransferDataFlavors() = arrayOf(CONNECTION_FLAVOR)
                override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == CONNECTION_FLAVOR
                override fun getTransferData(flavor: DataFlavor): Any =
                    if (flavor == CONNECTION_FLAVOR) id else throw UnsupportedFlavorException(flavor)
            }
        }

        override fun exportDone(source: JComponent, data: Transferable?, action: Int) {
            draggedId = null
        }

        private fun target(support: TransferSupport): ConnectionNode? {
            if (!support.isDrop || !support.isDataFlavorSupported(CONNECTION_FLAVOR)) return null
            val node = (support.dropLocation as? JTree.DropLocation)?.path?.lastPathComponent as? ConnectionNode ?: return null
            return node.takeIf { draggedId != null && it.configId != draggedId }
        }

        override fun canImport(support: TransferSupport): Boolean = target(support) != null

        override fun importData(support: TransferSupport): Boolean {
            val target = target(support) ?: return false
            settings.moveConnection(draggedId ?: return false, root.getIndex(target))
            return true
        }

        // Keep the tree's original clipboard behavior.
        override fun exportToClipboard(comp: JComponent, clip: Clipboard, action: Int) {
            delegate?.exportToClipboard(comp, clip, action)
        }
    }

    private fun connect(node: ConnectionNode) {
        scope.launch(UiDispatcher) {
            node.error = null
            showPlaceholder(node, StatusNode("Connecting…", StatusNode.Kind.LOADING))
            try {
                val session = manager.connect(node.configId)
                populate(node, session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = LdapErrors.describe(e)
                node.error = message
                showPlaceholder(node, StatusNode(message, StatusNode.Kind.ERROR))
                LdapNotifications.error(project, "Cannot connect to ${node.config.displayName}", message)
            }
        }
    }

    private fun populate(node: ConnectionNode, session: LdapSession) {
        if (node.parent == null) return
        discardCursors(node)
        node.populated = true
        node.error = null
        node.removeAllChildren()
        session.baseDns.forEach { node.add(EntryNode(node.configId, it, isBase = true)) }
        model.nodeStructureChanged(node)
        tree.expandPath(TreePath(node.path))
    }

    private fun showPlaceholder(node: LdapTreeNode, status: StatusNode) {
        if (node.childCount == 1 && (node.firstChild as? StatusNode)?.let { it.kind == status.kind && it.text == status.text } == true) return
        node.removeAllChildren()
        node.add(status)
        model.nodeStructureChanged(node)
    }

    private fun onConnectionStateChanged(configId: String) {
        val node = connectionNode(configId) ?: return
        if (!manager.isConnected(configId) && !manager.isConnecting(configId) && node.populated) {
            discardCursors(node)
            node.populated = false
            tree.collapsePath(TreePath(node.path))
            showPlaceholder(node, StatusNode("Connecting…", StatusNode.Kind.LOADING))
        }
        model.nodeChanged(node)
    }

    private fun connectionNode(configId: String): ConnectionNode? =
        root.children().toList().filterIsInstance<ConnectionNode>().firstOrNull { it.configId == configId }

    private fun addConnection() {
        val config = LdapConnectionConfig()
        val dialog = ConnectionDialog(project, config, isNew = true)
        if (dialog.showAndGet()) {
            settings.addOrUpdate(dialog.result)
            LdapCredentials.setPassword(config.id, dialog.password)
        }
    }

    private fun editConnection(node: ConnectionNode) {
        val dialog = ConnectionDialog(project, node.config.copy(), isNew = false)
        if (dialog.showAndGet()) {
            dialog.passwordIfChanged?.let { LdapCredentials.setPassword(node.configId, it) }
            if (dialog.passwordIfChanged != null) manager.disconnect(node.configId)
            settings.addOrUpdate(dialog.result)
        }
    }

    private fun removeConnection(node: ConnectionNode) {
        val answer = Messages.showYesNoDialog(project, "Remove connection '${node.config.displayName}'?", "Remove LDAP Connection", Messages.getQuestionIcon())
        if (answer != Messages.YES) return
        manager.disconnect(node.configId)
        settings.remove(node.configId)
        LdapCredentials.setPassword(node.configId, null)
    }

    // ---------------------------------------------------------------- progressive loading

    private fun childFilter(node: EntryNode): Filter =
        node.childFilter?.let { Filter.create(it) } ?: Filter.createPresenceFilter("objectClass")

    /**
     * Loads the next page of children of [node] (or the first page when [reset]).
     * The search itself runs on a background thread; only the tree model update happens on the EDT.
     */
    fun loadChildren(node: EntryNode, reset: Boolean) {
        val session = manager.session(node.configId)
        if (session == null) {
            node.connectionNode?.let { connect(it) }
            return
        }
        if (node.loadState == LoadState.LOADING && !reset) return
        if (reset || node.cursor == null) {
            discardCursors(node, includeSelf = true)
            node.generation++
            node.truncated = false
            node.loadedCount = 0
            showPlaceholder(node, StatusNode("Loading…", StatusNode.Kind.LOADING))
        } else {
            removeTransientChildren(node)
            model.insertNodeInto(StatusNode("Loading…", StatusNode.Kind.LOADING), node, node.childCount)
        }
        node.loadState = LoadState.LOADING
        val generation = node.generation
        val cursor = node.cursor
            ?: session.cursor(node.dn, SearchScope.ONE, childFilter(node), TREE_ATTRIBUTES).also { node.cursor = it }

        scope.launch {
            val result = try {
                Result.success(cursor.nextPage().map { toChildNode(node.configId, it) }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            withContext(UiDispatcher) {
                if (node.generation != generation || node.root !== root) return@withContext
                applyPage(node, cursor, result)
            }
        }
    }

    private fun applyPage(node: EntryNode, cursor: PagedSearch, result: Result<List<EntryNode>>) {
        removeTransientChildren(node)
        result.onSuccess { children ->
            val start = node.childCount
            children.forEach { node.add(it) }
            if (children.isNotEmpty()) model.nodesWereInserted(node, IntArray(children.size) { start + it })
            node.loadedCount += children.size
            node.truncated = cursor.truncated
            node.loadState = LoadState.LOADED
            if (!cursor.exhausted) model.insertNodeInto(LoadMoreNode(node), node, node.childCount)
            if (cursor.truncated) {
                model.insertNodeInto(
                    StatusNode("Size limit reached — use 'Filter Children…' to narrow the result", StatusNode.Kind.INFO),
                    node, node.childCount,
                )
            }
            if (node.loadedCount == 0 && node.childFilter != null) {
                model.insertNodeInto(StatusNode("No entries match ${node.childFilter}", StatusNode.Kind.INFO), node, node.childCount)
            }
        }.onFailure { e ->
            node.loadState = if (node.loadedCount > 0) LoadState.LOADED else LoadState.NOT_LOADED
            model.insertNodeInto(StatusNode(LdapErrors.describe(e), StatusNode.Kind.ERROR), node, node.childCount)
            if (node.loadedCount > 0) model.insertNodeInto(LoadMoreNode(node), node, node.childCount)
        }
        model.nodeChanged(node)
    }

    private fun removeTransientChildren(node: EntryNode) {
        node.children().toList()
            .filter { it is StatusNode || it is LoadMoreNode }
            .forEach { model.removeNodeFromParent(it as MutableTreeNode) }
    }

    private fun toChildNode(configId: String, entry: SearchResultEntry): EntryNode {
        val hasSubordinates = entry.getAttributeValue("hasSubordinates")
        val numSubordinates = entry.getAttributeValueAsInteger("numSubordinates")
        val mayHaveChildren = when {
            hasSubordinates != null -> !hasSubordinates.equals("FALSE", ignoreCase = true)
            numSubordinates != null -> numSubordinates > 0
            else -> true
        }
        return EntryNode(configId, entry.dn, isBase = false, entry.objectClassValues?.toList().orEmpty(), mayHaveChildren)
    }

    /** Gives connections held by cursors below [node] back to the pool. */
    private fun discardCursors(node: LdapTreeNode, includeSelf: Boolean = true) {
        val cursors = node.depthFirstEnumeration().toList()
            .filterIsInstance<EntryNode>()
            .filter { includeSelf || it !== node }
            .mapNotNull { n -> n.cursor.also { n.cursor = null; n.loadState = LoadState.NOT_LOADED } }
        if (cursors.isNotEmpty()) {
            ApplicationManager.getApplication().executeOnPooledThread { cursors.forEach { it.releaseIfIdle(force = true) } }
        }
    }

    private fun refresh(node: LdapTreeNode) {
        when (node) {
            is ConnectionNode -> manager.session(node.configId)?.let { populate(node, it) } ?: connect(node)
            is EntryNode -> {
                loadChildren(node, reset = true)
                tree.expandPath(TreePath(node.path))
            }
            is LoadMoreNode -> loadChildren(node.owner, reset = false)
            is StatusNode -> (node.parent as? LdapTreeNode)?.let { refresh(it) }
        }
    }

    private fun findEntryNodes(configId: String, dn: String): List<EntryNode> =
        root.breadthFirstEnumeration().toList().filterIsInstance<EntryNode>()
            .filter { it.configId == configId && LdapDn.same(it.dn, dn) }

    private fun onChildrenChanged(configId: String, parentDn: String) {
        findEntryNodes(configId, parentDn).forEach(::refreshChildren)
    }

    private fun refreshChildren(node: EntryNode) {
        if (node.loadState != LoadState.NOT_LOADED || tree.isExpanded(TreePath(node.path))) {
            loadChildren(node, reset = true)
        } else if (node.childCount == 0) {
            model.insertNodeInto(StatusNode("Loading…", StatusNode.Kind.LOADING), node, 0)
        }
    }

    /** Applies many changes with a single traversal of the tree. */
    private fun onEntriesChanged(configId: String, changes: BulkChanges) {
        val nodes = root.breadthFirstEnumeration().toList().filterIsInstance<EntryNode>().filter { it.configId == configId }
        val removed = mutableSetOf<EntryNode>()
        for (node in nodes) {
            if (node in removed || node.parent == null) continue
            val key = BulkChanges.normalizeDn(node.dn)
            if (key in changes.deleted) {
                node.breadthFirstEnumeration().toList().filterIsInstance<EntryNode>().forEach { removed += it }
                discardCursors(node)
                val parent = node.parent as? EntryNode
                model.removeNodeFromParent(node)
                parent?.let {
                    it.loadedCount = (it.loadedCount - 1).coerceAtLeast(0)
                    model.nodeChanged(it)
                }
            }
        }
        for (node in nodes) {
            if (node in removed || node.parent == null) continue
            if (BulkChanges.normalizeDn(node.dn) in changes.parents) refreshChildren(node)
        }
    }

    private fun onEntryDeleted(configId: String, dn: String) {
        for (node in findEntryNodes(configId, dn)) {
            discardCursors(node)
            val parent = node.parent as? EntryNode
            model.removeNodeFromParent(node)
            parent?.let {
                it.loadedCount = (it.loadedCount - 1).coerceAtLeast(0)
                model.nodeChanged(it)
            }
        }
    }

    private fun filterChildren(node: EntryNode) {
        val value = Messages.showInputDialog(
            project,
            "LDAP filter applied to the children of\n${node.dn}\n(e.g. (cn=john*) or (objectClass=person)). Leave empty to clear.",
            "Filter Children", AllIcons.General.Filter, node.childFilter ?: "",
            object : InputValidatorEx {
                override fun getErrorText(inputString: String): String? {
                    if (inputString.isBlank()) return null
                    return try {
                        Filter.create(inputString.trim()); null
                    } catch (e: LDAPException) {
                        e.exceptionMessage
                    }
                }
            },
        ) ?: return
        setChildFilter(node, value.trim().ifEmpty { null })
    }

    private fun setChildFilter(node: EntryNode, filter: String?) {
        node.childFilter = filter
        loadChildren(node, reset = true)
        tree.expandPath(TreePath(node.path))
    }

    private fun openSearch(baseDn: String?, configId: String?) {
        val dialog = SearchDialog(project, configId, baseDn)
        if (dialog.showAndGet()) SearchResultsPanel.show(project, dialog.params)
    }

    // ---------------------------------------------------------------- actions

    private fun action(
        text: String,
        icon: Icon?,
        enabled: () -> Boolean = { true },
        visible: () -> Boolean = { true },
        perform: () -> Unit,
    ) = object : DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isVisible = visible()
            e.presentation.isEnabled = enabled()
        }

        override fun actionPerformed(e: AnActionEvent) = perform()
    }

    private fun createToolbarGroup() = DefaultActionGroup().apply {
        add(addConnectionAction)
        add(editConnectionAction)
        add(removeConnectionAction)
        addSeparator()
        add(connectAction)
        add(disconnectAction)
        add(refreshAction)
        addSeparator()
        add(searchAction)
        add(savedSearchesGroup)
        add(filterAction)
        addSeparator()
        add(newEntryAction)
        add(deleteAction)
        add(importLdifAction)
        add(exportLdifAction)
        addSeparator()
        add(schemaAction)
        add(operationLogAction)
        add(operationalToggle)
        add(CommonActionsManager.getInstance().createCollapseAllAction(DefaultTreeExpander(tree), tree))
    }

    private fun createPopupGroup() = DefaultActionGroup().apply {
        add(openEntryAction)
        add(loadMoreAction)
        add(showGroupsAction)
        add(showMembersAction)
        addSeparator()
        add(accountStatusAction)
        addSeparator()
        add(newEntryAction)
        add(duplicateAction)
        add(renameAction)
        add(copyMoveAction)
        add(deleteAction)
        add(copyDnAction)
        add(compareAction)
        addSeparator()
        add(refreshAction)
        add(filterAction)
        add(clearFilterAction)
        add(searchAction)
        add(savedSearchesGroup)
        addSeparator()
        add(importLdifAction)
        add(exportLdifAction)
        addSeparator()
        add(connectAction)
        add(disconnectAction)
        add(editConnectionAction)
        add(moveUpAction)
        add(moveDownAction)
        add(schemaAction)
    }

    override fun dispose() {
        discardCursors(root)
    }

    companion object {
        val TREE_ATTRIBUTES = listOf("objectClass", "hasSubordinates", "numSubordinates")
        private val CONNECTION_FLAVOR = DataFlavor("${DataFlavor.javaJVMLocalObjectMimeType};class=java.lang.String", "LDAP connection")
    }
}
