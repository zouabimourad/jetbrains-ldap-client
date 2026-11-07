package com.mouradzouabi.ldapclient.ui.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class LdapToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val browser = LdapBrowserPanel(project)
        val content = ContentFactory.getInstance().createContent(browser, null, false).apply {
            isCloseable = false
            setDisposer(browser)
        }
        toolWindow.contentManager.addContent(content)
    }
}
