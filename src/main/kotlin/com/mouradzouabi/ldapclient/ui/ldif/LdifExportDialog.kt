package com.mouradzouabi.ldapclient.ui.ldif

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.unboundid.ldap.sdk.Filter
import com.unboundid.ldap.sdk.LDAPException
import com.mouradzouabi.ldapclient.core.ExportScope
import com.mouradzouabi.ldapclient.core.LdapDn
import com.mouradzouabi.ldapclient.core.LdifExportOptions
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JComponent

/** Options for exporting an entry (and optionally its subtree) to an LDIF file. */
class LdifExportDialog(
    private val project: Project,
    private val dn: String,
) : DialogWrapper(project, true) {
    private val properties = PropertiesComponent.getInstance()
    private val scopeCombo = ComboBox(ExportScope.entries.toTypedArray()).apply {
        selectedItem = ExportScope.entries.firstOrNull { it.name == properties.getValue(LAST_SCOPE) } ?: ExportScope.SUBTREE
    }
    private val filterField = JBTextField(DEFAULT_FILTER)
    private val attributesField = JBTextField("*").apply {
        emptyText.text = "* = all normal attributes; space or comma separated list"
    }
    private val operationalBox = JBCheckBox("Include operational attributes", false).apply {
        toolTipText = "Server-maintained attributes such as createTimestamp, entryUUID or memberOf. Usually not wanted for re-import."
    }
    private val openBox = JBCheckBox("Open file after export", properties.getBoolean(LAST_OPEN, true))
    private val fileField = TextFieldWithBrowseButton().apply {
        text = defaultFile().toString()
        addActionListener { chooseFile() }
    }

    init {
        title = "Export LDIF"
        setOKButtonText("Export")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Entry:") { label(dn.ifEmpty { "<root DSE>" }) }
        row("Scope:") { cell(scopeCombo) }
        row("Filter:") { cell(filterField).align(AlignX.FILL) }
        row("Attributes:") { cell(attributesField).align(AlignX.FILL) }
        row { cell(operationalBox) }
        row("File:") { cell(fileField).align(AlignX.FILL).focused() }
        row { cell(openBox) }
    }.apply { preferredSize = preferredSize.apply { width = maxOf(width, 560) } }

    private fun defaultFile(): Path {
        val dir = properties.getValue(LAST_DIR)?.let { Paths.get(it) } ?: project.basePath?.let { Paths.get(it) } ?: Paths.get(System.getProperty("user.home"))
        val rdn = LdapDn.rdn(dn).ifEmpty { "export" }
        return dir.resolve(FileUtil.sanitizeFileName(rdn, false) + ".ldif")
    }

    private fun chooseFile() {
        val current = Paths.get(fileField.text.ifBlank { defaultFile().toString() })
        val descriptor = FileSaverDescriptor("Export LDIF", "Choose the LDIF file to write", "ldif")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val baseDir = current.parent?.let { com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(it) }
        dialog.save(baseDir, current.fileName?.toString())?.file?.let { fileField.text = it.path }
    }

    // Kotlin UI DSL dialogs don't re-validate by default, which would keep OK disabled after a fixed error.
    override fun continuousValidation() = true

    override fun doValidate(): ValidationInfo? {
        try {
            Filter.create(filterField.text.trim().ifEmpty { DEFAULT_FILTER })
        } catch (e: LDAPException) {
            return ValidationInfo("Invalid filter: ${e.diagnosticMessage ?: e.message}", filterField)
        }
        val path = fileField.text.trim()
        if (path.isEmpty()) return ValidationInfo("Choose a file", fileField.textField)
        val file = runCatching { Paths.get(path) }.getOrNull() ?: return ValidationInfo("Invalid path", fileField.textField)
        if (file.parent == null || !java.nio.file.Files.isDirectory(file.parent)) return ValidationInfo("Directory does not exist", fileField.textField)
        if (java.nio.file.Files.isDirectory(file)) return ValidationInfo("Path is a directory", fileField.textField)
        return null
    }

    override fun doOKAction() {
        properties.setValue(LAST_SCOPE, (scopeCombo.selectedItem as ExportScope).name)
        properties.setValue(LAST_OPEN, openBox.isSelected, true)
        file.parent?.let { properties.setValue(LAST_DIR, it.toString()) }
        super.doOKAction()
    }

    val file: Path get() = Paths.get(fileField.text.trim()).toAbsolutePath()

    val openAfterExport: Boolean get() = openBox.isSelected

    val options: LdifExportOptions
        get() = LdifExportOptions(
            scope = scopeCombo.selectedItem as ExportScope,
            filter = Filter.create(filterField.text.trim().ifEmpty { DEFAULT_FILTER }),
            attributes = attributesField.text.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() },
            includeOperational = operationalBox.isSelected,
        )

    private companion object {
        const val DEFAULT_FILTER = "(objectClass=*)"
        const val LAST_SCOPE = "ldapClient.export.scope"
        const val LAST_OPEN = "ldapClient.export.open"
        const val LAST_DIR = "ldapClient.export.dir"
    }
}
