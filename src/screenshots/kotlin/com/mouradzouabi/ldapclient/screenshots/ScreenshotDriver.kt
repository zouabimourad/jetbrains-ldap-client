package com.mouradzouabi.ldapclient.screenshots

import com.intellij.credentialStore.PasswordSafeSettings
import com.intellij.credentialStore.ProviderType
import com.intellij.ide.actions.QuickChangeLookAndFeel
import com.intellij.ide.ui.LafManager
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.UIUtil
import com.mouradzouabi.ldapclient.service.LdapConnectionManager
import com.mouradzouabi.ldapclient.core.LdifRunOptions
import com.mouradzouabi.ldapclient.model.AuthMethod
import com.mouradzouabi.ldapclient.model.LdapConnectionConfig
import com.mouradzouabi.ldapclient.model.LdapCredentials
import com.mouradzouabi.ldapclient.model.LdapSettings
import com.mouradzouabi.ldapclient.model.SecurityMode
import com.mouradzouabi.ldapclient.core.SchemaKind
import com.mouradzouabi.ldapclient.model.SavedSearch
import com.mouradzouabi.ldapclient.ui.EntryOperations
import com.mouradzouabi.ldapclient.ui.EntryRef
import com.mouradzouabi.ldapclient.ui.EntryTools
import com.mouradzouabi.ldapclient.ui.log.OperationLogPanel
import com.mouradzouabi.ldapclient.ui.schema.LdapSchemaVirtualFile
import com.mouradzouabi.ldapclient.ui.search.LdapFilterField
import com.mouradzouabi.ldapclient.ui.search.SearchDialog
import com.mouradzouabi.ldapclient.ui.connection.ConnectionDialog
import com.mouradzouabi.ldapclient.ui.dialogs.PasswordValueDialog
import com.mouradzouabi.ldapclient.ui.editor.LdapEntryEditor
import com.mouradzouabi.ldapclient.ui.editor.LdapEntryVirtualFile
import com.mouradzouabi.ldapclient.ui.ldif.LdifRunPanel
import com.mouradzouabi.ldapclient.ui.ldif.LdifSource
import com.mouradzouabi.ldapclient.ui.membership.MembershipNavigator
import com.mouradzouabi.ldapclient.ui.search.SearchParams
import com.mouradzouabi.ldapclient.ui.search.SearchResultsPanel
import com.mouradzouabi.ldapclient.ui.toolwindow.ConnectionNode
import com.mouradzouabi.ldapclient.ui.toolwindow.EntryNode
import com.mouradzouabi.ldapclient.ui.toolwindow.LdapBrowserPanel
import com.mouradzouabi.ldapclient.ui.toolwindow.LdapResultsToolWindowFactory
import com.mouradzouabi.ldapclient.ui.toolwindow.LoadState
import com.unboundid.ldap.sdk.SearchScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.io.File
import java.nio.file.Path
import javax.swing.JFrame
import javax.swing.tree.TreeNode
import javax.swing.tree.TreePath
import kotlin.time.Duration.Companion.minutes

/** Dev-only plugin: drives a sandbox IDE through the LDAP Client features and saves Marketplace screenshots. */
class ScreenshotDriver : ProjectActivity {
    override suspend fun execute(project: Project) {
        val out = System.getProperty(OUT_PROPERTY) ?: return
        project.service<DriverScope>().scope.launch { Scenario(project, File(out)).runAndExit() }
    }

    companion object {
        const val OUT_PROPERTY = "ldapclient.screenshots.out"
    }
}

@Service(Service.Level.PROJECT)
class DriverScope(val scope: CoroutineScope)

private class Scenario(private val project: Project, private val outDir: File) {
    private val log = logger<ScreenshotDriver>()
    private val edt = Dispatchers.EDT + ModalityState.any().asContextElement()
    private val settings = LdapSettings.getInstance()
    private val themes = System.getProperty("ldapclient.screenshots.themes", "Light").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    private val width = Integer.getInteger("ldapclient.screenshots.width", 1280)
    private val height = Integer.getInteger("ldapclient.screenshots.height", 800)

    private lateinit var directory: DemoDirectory
    private lateinit var demo: LdapConnectionConfig
    private lateinit var production: LdapConnectionConfig
    private lateinit var frame: JFrame
    private lateinit var toolWindow: ToolWindow
    private lateinit var browser: LdapBrowserPanel
    private lateinit var suffix: String

    suspend fun runAndExit() {
        val errorFile = File(outDir, "driver-error.txt")
        errorFile.delete()
        try {
            withTimeout(15.minutes) { run() }
            log.info("Screenshots written to $outDir")
        } catch (e: Throwable) {
            log.warn("Screenshot driver failed", e)
            outDir.mkdirs()
            errorFile.writeText(e.stackTraceToString())
        } finally {
            if (!java.lang.Boolean.getBoolean("ldapclient.screenshots.keepOpen")) {
                ApplicationManager.getApplication().invokeLater({ ApplicationManager.getApplication().exit(true, true, false) }, ModalityState.any())
            }
        }
    }

    private suspend fun run() {
        // Never touch the real OS keychain from the sandbox.
        service<PasswordSafeSettings>().providerType = ProviderType.MEMORY_ONLY
        com.intellij.openapi.editor.ex.EditorSettingsExternalizable.getInstance().isShowIntentionBulb = false

        directory = withContext(Dispatchers.IO) { DemoDirectory() }
        val snapshot = directory.server.createSnapshot()
        smartReadAction(project) {}
        delay(3000)

        withContext(edt) {
            frame = WindowManager.getInstance().getFrame(project) as JFrame
            frame.extendedState = Frame.NORMAL
            val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            frame.setBounds(screen.x + 20, screen.y + 20, width, height)
            setUpConnections()
            toolWindow = ToolWindowManager.getInstance(project).getToolWindow("LDAP")!!
        }

        try {
            for (theme in themes) {
                suffix = if (themes.size == 1) "" else "-" + theme.lowercase().replace(' ', '-')
                directory.server.restoreSnapshot(snapshot)
                withContext(edt) { applyTheme(theme) }
                delay(1500)
                pass()
            }
        } finally {
            withContext(edt) { settings.connections.toList().forEach { settings.remove(it.id) } }
            directory.close()
        }
    }

    private fun setUpConnections() {
        settings.connections.toList().forEach { settings.remove(it.id) }
        settings.showOperationalAttributes = true
        production = LdapConnectionConfig().apply {
            name = "Production (LDAPS)"
            host = "ldap.acme.example"
            port = 636
            security = SecurityMode.LDAPS
            authMethod = AuthMethod.SIMPLE
            bindDn = "cn=admin,dc=acme,dc=example"
            baseDn = "dc=acme,dc=example"
            readOnly = true
        }
        val ad = LdapConnectionConfig().apply {
            name = "Active Directory"
            host = "dc01.corp.acme.example"
            port = 389
            security = SecurityMode.START_TLS
            authMethod = AuthMethod.SIMPLE
            bindDn = "CN=svc-ldap,OU=Service Accounts,DC=corp,DC=acme,DC=example"
            baseDn = "DC=corp,DC=acme,DC=example"
        }
        demo = LdapConnectionConfig().apply {
            name = "ACME Staging"
            host = "localhost"
            port = directory.port
            authMethod = AuthMethod.ANONYMOUS
            baseDn = DemoDirectory.BASE
            pageSize = 100
        }
        settings.addOrUpdate(production)
        settings.addOrUpdate(ad)
        settings.addOrUpdate(demo)
        LdapCredentials.setPassword(production.id, "not-a-real-password")
    }

    private fun applyTheme(name: String) {
        val laf = LafManager.getInstance()
        laf.autodetect = false
        val info = laf.installedThemes.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: error("Unknown theme '$name', available: ${laf.installedThemes.map { it.name }.toList()}")
        if (laf.currentUIThemeLookAndFeel?.id != info.id) QuickChangeLookAndFeel.switchLafAndUpdateUI(laf, info, false)
    }

    // ------------------------------------------------------------------ scenario

    private suspend fun pass() {
        withContext(edt) { resetWorkspace() }
        browser = await("LDAP browser") { toolWindow.contentManager.contents.firstNotNullOfOrNull { it.component as? LdapBrowserPanel } }
        withContext(edt) { setToolWindowWidth(440) }

        // 1. Tree + entry editor
        val connection = await("demo connection node") { connectionNode() }
        withContext(edt) { browser.tree.expandPath(TreePath(connection.path)) }
        val base = await("base entry") { childEntry(connectionNode(), DemoDirectory.BASE) }
        expandAndWait(base)
        val groups = await("ou=groups") { childEntry(base, DemoDirectory.GROUPS) }
        expandAndWait(groups)
        val people = await("ou=people") { childEntry(base, DemoDirectory.PEOPLE) }
        expandAndWait(people)
        val alice = await("featured user") { childEntry(people, DemoDirectory.FEATURED_DN) }
        withContext(edt) {
            val path = TreePath(alice.path)
            browser.tree.selectionPath = path
            browser.tree.scrollRowToVisible(0)
            browser.tree.scrollPathToVisible(path)
            EntryOperations.open(project, demo.id, DemoDirectory.FEATURED_DN)
        }
        awaitEditorLoaded(DemoDirectory.FEATURED_DN)
        capture("01-browser-and-entry-editor")

        // 2. Password editor
        val current = directory.server.getEntry(DemoDirectory.FEATURED_DN).getAttributeValueBytes("userPassword")
        showDialog("02-password-editor") {
            PasswordValueDialog(project, "userPassword", current).also { dialog ->
                ApplicationManager.getApplication().invokeLater({
                    UIUtil.findComponentsOfType(dialog.contentPanel, JBPasswordField::class.java).forEach { it.text = "Correct-Horse-Battery-42" }
                }, ModalityState.any())
            }
        }

        // 3. Group membership: groups of the user (direct, nested, POSIX) shown from the tree
        withContext(edt) {
            EntryOperations.open(project, demo.id, DemoDirectory.ENGINEERING_DN)
        }
        awaitEditorLoaded(DemoDirectory.ENGINEERING_DN)
        withContext(edt) {
            browser.tree.selectionPath = TreePath(alice.path)
            MembershipNavigator.showGroups(project, demo.id, DemoDirectory.FEATURED_DN, browser.tree)
        }
        val popup = await("groups popup", 20_000) { JBPopupFactory.getInstance().getChildPopups(frame.rootPane).firstOrNull() }
        delay(500)
        withContext(edt) {
            // Keep the popup inside the captured frame.
            val root = frame.rootPane
            val origin = root.locationOnScreen
            val at = popup.locationOnScreen
            val size = popup.size
            val x = at.x.coerceAtMost(origin.x + root.width - size.width - 24)
            val y = at.y.coerceAtMost(origin.y + root.height - size.height - 40)
            popup.setLocation(Point(x, y))
        }
        delay(500)
        capture("03-group-membership")
        withContext(edt) { JBPopupFactory.getInstance().getChildPopups(frame.rootPane).forEach { it.cancel() } }

        // 4. Search
        withContext(edt) {
            SearchResultsPanel.show(
                project,
                SearchParams(
                    demo.id, demo.displayName, DemoDirectory.PEOPLE, SearchScope.SUB,
                    "(&(departmentNumber=Engineering)(l=Paris))",
                    listOf("cn", "title", "telephoneNumber"), 1000, false,
                ),
            )
            setResultsHeight(380)
        }
        delay(2500)
        capture("04-search")

        // 5. LDIF script
        val ldif = withContext(Dispatchers.IO) {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(project.basePath!!, "onboarding.ldif"))
        } ?: error("onboarding.ldif not found in the demo project")
        withContext(edt) {
            FileEditorManager.getInstance(project).openFile(ldif, true)
            LdifRunPanel.show(project, LdifSource.ofFile(ldif), demo.id, demo.displayName, LdifRunOptions())
        }
        await("LDIF run") { results().contentManager.selectedContent?.component.let { it as? LdifRunPanel }?.takeIf { !it.isRunning } }
        delay(1500)
        capture("05-ldif-script")

        // 6. Connection settings
        withContext(edt) {
            results().hide()
            setToolWindowWidth(440)
        }
        // The LDIF run refreshed the tree: show the new ou=contractors.
        val refreshedBase = await("base entry") { childEntry(connectionNode(), DemoDirectory.BASE) }
        expandAndWait(refreshedBase)
        await("ou=contractors") { childEntry(refreshedBase, "ou=contractors,${DemoDirectory.BASE}") }.let { expandAndWait(it) }
        withContext(edt) { browser.tree.selectionPath = connectionNode(production.id)?.let { TreePath(it.path) } }
        showDialog("06-connection-settings") { ConnectionDialog(project, production.copy(), isNew = false) }

        // 7. Search dialog: filter highlighting, completion and saved searches
        withContext(edt) {
            settings.saveSearch(SavedSearch().apply {
                name = "Paris engineers"
                configId = demo.id
                baseDn = DemoDirectory.PEOPLE
                filter = "(&(departmentNumber=Engineering)(l=Paris))"
                attributes = "cn, title, telephoneNumber"
            })
            browser.tree.selectionPath = connectionNode()?.let { TreePath(it.path) }
        }
        showDialog("07-search-filter") {
            SearchDialog(project, demo.id, DemoDirectory.PEOPLE).also { dialog ->
                ApplicationManager.getApplication().invokeLater({
                    UIUtil.findComponentsOfType(dialog.contentPanel, LdapFilterField::class.java).forEach {
                        it.text = "(&(objectClass=inetOrgPerson)(|(cn=alice*)(mail=*@acme.example))(!(title=Intern)))"
                    }
                }, ModalityState.any())
            }
        }

        // 8. Schema browser
        withContext(edt) {
            FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
            LdapSchemaVirtualFile.open(project, demo.id, SchemaKind.OBJECT_CLASS, "inetOrgPerson")
        }
        delay(3000)
        capture("08-schema-browser")

        // 9. Compare two entries
        val other = withContext(Dispatchers.IO) {
            directory.server.getEntry(DemoDirectory.ENGINEERING_DN).getAttributeValues("member")
                .first { it.startsWith("uid=", ignoreCase = true) && !it.equals(DemoDirectory.FEATURED_DN, ignoreCase = true) }
        }
        withContext(edt) {
            FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
            EntryTools.compare(project, EntryRef(demo.id, DemoDirectory.FEATURED_DN), EntryRef(demo.id, other))
        }
        delay(4000)
        capture("09-compare-entries")

        // 10. Operation log (the LDIF script above was logged)
        withContext(edt) { FileEditorManagerEx.getInstanceEx(project).closeAllFiles() }
        withContext(edt) { OperationLogPanel.show(project) }
        delay(1500)
        withContext(edt) {
            UIUtil.findComponentsOfType(results().component, com.intellij.ui.table.JBTable::class.java).firstOrNull()?.let {
                if (it.rowCount > 0) it.setRowSelectionInterval(0, 0)
            }
            setResultsHeight(380)
        }
        delay(1500)
        capture("10-operation-log")
        withContext(edt) { settings.removeSearch("Paris engineers") }
    }

    // ------------------------------------------------------------------ helpers

    private fun resetWorkspace() {
        FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
        val manager = ToolWindowManager.getInstance(project)
        manager.toolWindowIds.filter { it != "LDAP" }.forEach { manager.getToolWindow(it)?.hide() }
        LdapResultsToolWindowFactory.toolWindow(project)?.contentManager?.removeAllContents(true)
        LdapConnectionManager.getInstance().disconnect(demo.id)
        toolWindow.show()
    }

    private fun results(): ToolWindow = LdapResultsToolWindowFactory.toolWindow(project)!!

    private fun setResultsHeight(target: Int) {
        val ex = results() as? ToolWindowEx ?: return
        val current = ex.component.height
        if (current > 0) ex.stretchHeight(target - current)
    }

    private fun setToolWindowWidth(target: Int) {
        val ex = toolWindow as? ToolWindowEx ?: return
        val current = ex.component.width
        if (current > 0) ex.stretchWidth(target - current)
    }

    private fun connectionNode(id: String = demo.id): ConnectionNode? {
        val root = browser.tree.model.root as TreeNode
        return root.children().toList().filterIsInstance<ConnectionNode>().firstOrNull { it.configId == id }
    }

    private fun childEntry(parent: TreeNode?, dn: String): EntryNode? =
        parent?.children()?.toList()?.filterIsInstance<EntryNode>()?.firstOrNull { it.dn.equals(dn, ignoreCase = true) }

    private suspend fun expandAndWait(node: EntryNode) {
        withContext(edt) { browser.tree.expandPath(TreePath(node.path)) }
        await("children of ${node.dn}") { node.takeIf { it.loadState == LoadState.LOADED } }
        // Replacing the "Loading…" placeholder may collapse the node again.
        await("expansion of ${node.dn}") {
            val path = TreePath(node.path)
            if (!browser.tree.isExpanded(path)) browser.tree.expandPath(path)
            node.takeIf { browser.tree.isExpanded(path) }
        }
    }

    private suspend fun awaitEditorLoaded(dn: String): LdapEntryEditor = await("editor of $dn") {
        FileEditorManager.getInstance(project).selectedEditors.filterIsInstance<LdapEntryEditor>()
            .firstOrNull { (it.file as LdapEntryVirtualFile).dn.equals(dn, ignoreCase = true) && it.isLoaded }
    }.also { delay(500) }

    private suspend fun showDialog(name: String, create: () -> DialogWrapper) {
        val dialog = withContext(edt) {
            create().also { ApplicationManager.getApplication().invokeLater({ it.show() }, ModalityState.any()) }
        }
        await("dialog for $name") { dialog.takeIf { it.window?.isShowing == true } }
        withContext(edt) {
            val window = dialog.window
            val root = frame.rootPane
            val origin = root.locationOnScreen
            window.setLocation(origin.x + (root.width - window.width) / 2, origin.y + ((root.height - window.height) / 2).coerceAtLeast(8))
        }
        delay(1200)
        capture(name)
        withContext(edt) { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
        delay(500)
    }

    private suspend fun capture(name: String) {
        withContext(edt) {
            NotificationsManager.getNotificationsManager().getNotificationsOfType(Notification::class.java, project).forEach { it.expire() }
            NotificationsManager.getNotificationsManager().getNotificationsOfType(Notification::class.java, null).forEach { it.expire() }
        }
        delay(400)
        withContext(edt) {
            val file = File(outDir, "$name$suffix.png")
            WindowCapture.capture(frame, file)
            log.info("Saved $file")
        }
    }

    private suspend fun <T : Any> await(what: String, timeoutMs: Long = 30_000, supplier: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            withContext(edt) { supplier() }?.let { return it }
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            delay(150)
        }
    }
}
