package com.mouradzouabi.ldapclient.ui.editor

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import com.mouradzouabi.ldapclient.core.LdapDn
import com.mouradzouabi.ldapclient.model.LdapSettings

/** In-memory file representing one directory entry; opened with [LdapEntryEditor]. */
class LdapEntryVirtualFile(
    val configId: String,
    val connectionName: String,
    val dn: String,
) : LightVirtualFile(LdapDn.rdn(dn).ifEmpty { "Root DSE" }, LdapEntryFileType, "") {

    init {
        isWritable = false
    }

    override fun getPath(): String = "ldap://$connectionName/$dn"

    companion object {
        fun open(project: Project, configId: String, dn: String) {
            val manager = FileEditorManager.getInstance(project)
            val existing = manager.openFiles.filterIsInstance<LdapEntryVirtualFile>()
                .firstOrNull { it.configId == configId && LdapDn.same(it.dn, dn) }
            val name = LdapSettings.getInstance().find(configId)?.displayName ?: configId
            OpenFileDescriptor(project, existing ?: LdapEntryVirtualFile(configId, name, dn)).navigate(true)
        }
    }
}
