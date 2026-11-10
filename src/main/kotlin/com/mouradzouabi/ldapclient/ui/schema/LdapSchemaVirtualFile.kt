package com.mouradzouabi.ldapclient.ui.schema

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import com.mouradzouabi.ldapclient.core.SchemaKind
import com.mouradzouabi.ldapclient.model.LdapSettings
import com.mouradzouabi.ldapclient.ui.editor.LdapEntryFileType

/** In-memory file opening the schema browser of one connection. */
class LdapSchemaVirtualFile(val configId: String, val connectionName: String) :
    LightVirtualFile("Schema: $connectionName", LdapEntryFileType, "") {

    init {
        isWritable = false
    }

    /** Element to select once the editor is created; consumed by [LdapSchemaEditor]. */
    @Volatile
    var requestedSelection: Pair<SchemaKind, String>? = null

    override fun getPath(): String = "ldap://$connectionName/cn=schema"

    companion object {
        /** Opens (or focuses) the schema browser of [configId], optionally selecting an element. */
        fun open(project: Project, configId: String, kind: SchemaKind? = null, name: String? = null) {
            val manager = FileEditorManager.getInstance(project)
            val file = manager.openFiles.filterIsInstance<LdapSchemaVirtualFile>().firstOrNull { it.configId == configId }
                ?: LdapSchemaVirtualFile(configId, LdapSettings.getInstance().find(configId)?.displayName ?: configId)
            file.requestedSelection = if (kind != null && name != null) kind to name else null
            OpenFileDescriptor(project, file).navigate(true)
            // The editor already existed: select in it directly.
            file.requestedSelection?.let { (k, n) ->
                file.requestedSelection = null
                manager.getEditors(file).filterIsInstance<LdapSchemaEditor>().firstOrNull()?.select(k, n)
            }
        }
    }
}
