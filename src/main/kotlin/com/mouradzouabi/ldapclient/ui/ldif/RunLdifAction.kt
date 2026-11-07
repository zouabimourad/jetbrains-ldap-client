package com.mouradzouabi.ldapclient.ui.ldif

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAwareAction

/**
 * Runs the LDIF script of the current editor (or its selection), or a `.ldif` file selected in the
 * project view, against an LDAP connection.
 */
class RunLdifAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val enabled = e.project != null && if (editor != null) {
            LdifFileType.matches(file) ||
                (editor.selectionModel.hasSelection() && (file == null || file.fileType == PlainTextFileType.INSTANCE))
        } else {
            LdifFileType.matches(file)
        }
        e.presentation.isEnabledAndVisible = enabled
        if (enabled && editor?.selectionModel?.hasSelection() == true) e.presentation.text = "Run Selected LDIF…"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        if (editor != null) {
            LdifRunner.runEditor(project, editor)
            return
        }
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        LdifRunner.runFile(project, file)
    }
}
