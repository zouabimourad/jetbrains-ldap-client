package com.mouradzouabi.ldapclient.ui.ldif

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import java.util.function.Function
import javax.swing.JComponent

/** Banner on LDIF files offering to run the script on an LDAP connection. */
class LdifEditorNotificationProvider : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (!LdifFileType.matches(file)) return null
        return Function { fileEditor ->
            val editor = (fileEditor as? TextEditor)?.editor ?: return@Function null
            EditorNotificationPanel(fileEditor, EditorNotificationPanel.Status.Info).apply {
                icon(AllIcons.Actions.Execute)
                val shortcut = KeymapUtil.getFirstKeyboardShortcutText(ActionManager.getInstance().getAction(ACTION_ID) ?: return@apply)
                text = "LDIF script" + if (shortcut.isNotEmpty()) " — run the whole file or the selection with $shortcut" else ""
                createActionLabel("Run on LDAP connection…") { LdifRunner.runEditor(project, editor) }
            }
        }
    }

    companion object {
        const val ACTION_ID = "LdapClient.RunLdif"
    }
}
