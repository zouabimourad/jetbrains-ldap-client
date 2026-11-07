package com.mouradzouabi.ldapclient.ui.ldif

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

object LdifFileType : FileType {
    private val ldifIcon: Icon by lazy { IconLoader.getIcon("/icons/ldap.svg", LdifFileType::class.java) }

    override fun getName() = "LDIF"
    override fun getDescription() = "LDAP Data Interchange Format"
    override fun getDefaultExtension() = "ldif"
    override fun getIcon(): Icon = ldifIcon
    override fun isBinary() = false
    override fun getCharset(file: VirtualFile, content: ByteArray): String = Charsets.UTF_8.name()

    fun matches(file: VirtualFile?): Boolean =
        file != null && !file.isDirectory && (file.fileType == this || file.extension.equals("ldif", ignoreCase = true))
}
