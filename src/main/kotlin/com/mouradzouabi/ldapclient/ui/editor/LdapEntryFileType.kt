package com.mouradzouabi.ldapclient.ui.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileType
import javax.swing.Icon

object LdapEntryFileType : FileType {
    override fun getName() = "LDAP Entry"
    override fun getDescription() = "LDAP directory entry"
    override fun getDefaultExtension() = ""
    override fun getIcon(): Icon = AllIcons.Nodes.Tag
    override fun isBinary() = true
    override fun isReadOnly() = true
}
