package com.mouradzouabi.ldapclient.ui.ldif

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.mouradzouabi.ldapclient.model.LdapSettings

/** Entry points for running LDIF scripts from files, editors and the browser. */
object LdifRunner {

    /** Runs the selection of [editor], or its whole document, against a connection chosen by the user. */
    fun runEditor(project: Project, editor: Editor, preferredConfigId: String? = null) {
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document)
        val selection = editor.selectionModel
        val source = if (selection.hasSelection() && selection.selectedText?.isNotBlank() == true) {
            val firstLine = document.getLineNumber(selection.selectionStart)
            val name = file?.name ?: "editor"
            LdifSource.ofText("$name (selection)", selection.selectedText!!, file, firstLine)
        } else {
            LdifSource.ofText(file?.name ?: "editor", document.text, file, 0)
        }
        start(project, source, preferredConfigId)
    }

    /** Asks for an LDIF file and imports it into a connection. */
    fun importFile(project: Project, preferredConfigId: String?) {
        val descriptor = FileChooserDescriptorFactory.singleFile()
            .withExtensionFilter("LDIF files", "ldif", "txt")
            .withTitle("Import LDIF")
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        runFile(project, file, preferredConfigId)
    }

    /** Runs a file; unsaved changes of an open document are used instead of the file on disk. */
    fun runFile(project: Project, file: VirtualFile, preferredConfigId: String? = null) {
        val fdm = FileDocumentManager.getInstance()
        val document = fdm.getCachedDocument(file)
        val source = if (document != null && fdm.isDocumentUnsaved(document)) {
            LdifSource.ofText(file.name, document.text, file, 0)
        } else {
            LdifSource.ofFile(file)
        }
        start(project, source, preferredConfigId)
    }

    fun start(project: Project, source: LdifSource, preferredConfigId: String?) {
        if (LdapSettings.getInstance().connections.isEmpty()) {
            Messages.showInfoMessage(project, "Add an LDAP connection in the LDAP tool window first.", "Run LDIF Script")
            return
        }
        val dialog = LdifRunDialog(project, source.title, preferredConfigId)
        if (!dialog.showAndGet()) return
        LdifRunPanel.show(project, source, dialog.config.id, dialog.config.displayName, dialog.options)
    }
}
