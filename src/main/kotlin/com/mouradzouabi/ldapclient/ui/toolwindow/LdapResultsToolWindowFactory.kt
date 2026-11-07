package com.mouradzouabi.ldapclient.ui.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.mouradzouabi.ldapclient.ui.RESULTS_TOOL_WINDOW_ID
import javax.swing.JComponent

/** Bottom tool window holding search results and LDIF runs; only available while it has tabs. */
class LdapResultsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun shouldBeAvailable(project: Project) = false

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {}

    override fun init(toolWindow: ToolWindow) {
        toolWindow.setToHideOnEmptyContent(true)
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                if (toolWindow.contentManager.contentCount == 0) toolWindow.isAvailable = false
            }
        })
    }

    companion object {
        fun toolWindow(project: Project): ToolWindow? = ToolWindowManager.getInstance(project).getToolWindow(RESULTS_TOOL_WINDOW_ID)

        fun show(project: Project, component: JComponent, title: String, description: String, disposable: Disposable) {
            val toolWindow = toolWindow(project) ?: return
            toolWindow.isAvailable = true
            val content = ContentFactory.getInstance().createContent(component, title, false).apply {
                isCloseable = true
                this.description = description
                setDisposer(disposable)
            }
            toolWindow.contentManager.addContent(content)
            toolWindow.contentManager.setSelectedContent(content)
            toolWindow.activate(null)
        }
    }
}
