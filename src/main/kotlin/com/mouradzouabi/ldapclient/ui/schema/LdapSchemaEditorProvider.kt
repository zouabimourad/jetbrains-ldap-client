package com.mouradzouabi.ldapclient.ui.schema

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class LdapSchemaEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is LdapSchemaVirtualFile
    override fun acceptRequiresReadAction() = false
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = LdapSchemaEditor(project, file as LdapSchemaVirtualFile)
    override fun getEditorTypeId() = "ldap-schema-browser"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
