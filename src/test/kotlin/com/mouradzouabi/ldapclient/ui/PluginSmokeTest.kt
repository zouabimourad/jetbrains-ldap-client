package com.mouradzouabi.ldapclient.ui

import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.unboundid.ldap.listener.InMemoryDirectoryServer
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig
import com.unboundid.ldap.listener.InMemoryListenerConfig
import com.mouradzouabi.ldapclient.model.LdapConnectionConfig
import com.mouradzouabi.ldapclient.model.LdapSettings
import com.mouradzouabi.ldapclient.service.LdapConnectionManager
import com.mouradzouabi.ldapclient.ui.editor.LdapEntryEditor
import com.mouradzouabi.ldapclient.ui.editor.LdapEntryEditorProvider
import com.mouradzouabi.ldapclient.ui.editor.LdapEntryVirtualFile
import com.mouradzouabi.ldapclient.ui.toolwindow.LdapBrowserPanel
import kotlinx.coroutines.runBlocking

class PluginSmokeTest : BasePlatformTestCase() {
    private lateinit var server: InMemoryDirectoryServer
    private lateinit var config: LdapConnectionConfig

    override fun setUp() {
        super.setUp()
        server = InMemoryDirectoryServer(InMemoryDirectoryServerConfig("dc=example,dc=com").apply {
            setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", 0))
        })
        server.add("dn: dc=example,dc=com", "objectClass: domain", "dc: example")
        server.add("dn: uid=a,dc=example,dc=com", "objectClass: inetOrgPerson", "uid: a", "cn: A", "sn: A", "userPassword: {SSHA}abc")
        server.startListening()
        config = LdapConnectionConfig().apply {
            name = "smoke"
            port = server.listenPort
            authMethod = com.mouradzouabi.ldapclient.model.AuthMethod.ANONYMOUS
        }
        LdapSettings.getInstance().addOrUpdate(config)
    }

    override fun tearDown() {
        try {
            LdapConnectionManager.getInstance().disconnect(config.id)
            LdapSettings.getInstance().remove(config.id)
            server.shutDown(true)
        } finally {
            super.tearDown()
        }
    }

    fun testLdifFileTypeHighlighterAndAction() {
        val file = myFixture.configureByText("script.ldif", "dn: cn=x,dc=example,dc=com\nchangetype: delete\n").virtualFile
        assertSame(com.mouradzouabi.ldapclient.ui.ldif.LdifFileType, file.fileType)
        val highlighter = com.intellij.openapi.fileTypes.SyntaxHighlighterFactory.getSyntaxHighlighter(file.fileType, project, file)
        assertTrue(highlighter is com.mouradzouabi.ldapclient.ui.ldif.LdifSyntaxHighlighter)
        val action = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("LdapClient.RunLdif")
        assertTrue(action is com.mouradzouabi.ldapclient.ui.ldif.RunLdifAction)
        val event = com.intellij.testFramework.TestActionEvent.createTestEvent(action, com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
            .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
            .add(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR, myFixture.editor)
            .add(com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE, file)
            .build())
        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
    }

    fun testConnectBrowseAndOpenEditor() {
        val session = runBlocking { LdapConnectionManager.getInstance().connect(config.id) }
        assertEquals(listOf("dc=example,dc=com"), session.baseDns)

        val panel = LdapBrowserPanel(project)
        Disposer.register(testRootDisposable, panel)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val file = LdapEntryVirtualFile(config.id, config.name, "uid=a,dc=example,dc=com")
        val providers = FileEditorProviderManager.getInstance().getProviderList(project, file)
        assertTrue(providers.any { it is LdapEntryEditorProvider })
        val editor = LdapEntryEditorProvider().createEditor(project, file) as LdapEntryEditor
        Disposer.register(testRootDisposable, editor)
        PlatformTestUtil.waitWithEventsDispatching("entry not loaded", { editor.isLoaded }, 10)
        assertFalse(editor.isModified)
    }
}
