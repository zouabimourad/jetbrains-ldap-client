package com.mouradzouabi.ldapclient.ui.dialogs

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.mouradzouabi.ldapclient.core.BulkModify
import com.mouradzouabi.ldapclient.core.BulkOperation
import com.mouradzouabi.ldapclient.core.ChangeRecords
import com.mouradzouabi.ldapclient.core.LdapSchema
import com.mouradzouabi.ldapclient.core.LdifRunOptions
import com.mouradzouabi.ldapclient.ui.ldif.LdifFileType
import com.mouradzouabi.ldapclient.ui.ldif.LdifRunPanel
import com.mouradzouabi.ldapclient.ui.ldif.LdifSource
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.ButtonGroup
import javax.swing.JComponent

/**
 * Applies one attribute change to many entries (e.g. the results of a search). The change is generated as an LDIF
 * script that is either run in the LDAP Results tool window or opened in an editor for review.
 */
class BulkModifyDialog(
    private val project: Project,
    private val configId: String,
    private val connectionName: String,
    schema: LdapSchema,
    private val selectedDns: List<String>,
    private val allDns: List<String>,
) : DialogWrapper(project, true) {

    private val operation = ComboBox(BulkOperation.entries.toTypedArray())
    private val attribute = TextFieldWithAutoCompletion.create(project, schema.attributeNames, true, "")
    private val values = JBTextArea(6, 40)
    private val selectedOnly = JBRadioButton("Selected entries (${selectedDns.size})", selectedDns.size > 1)
    private val all = JBRadioButton("All results (${allDns.size})", selectedDns.size <= 1)

    init {
        title = "Bulk Modify"
        setOKButtonText("Run")
        ButtonGroup().apply {
            add(selectedOnly)
            add(all)
        }
        selectedOnly.isEnabled = selectedDns.isNotEmpty()
        operation.addActionListener { values.isEnabled = (operation.selectedItem as BulkOperation).needsValues }
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Operation:") { cell(operation) }
        row("Attribute:") { cell(attribute).align(AlignX.FILL) }
        row("Values:") {
            cell(JBScrollPane(values)).align(Align.FILL).comment("One value per line")
        }.resizableRow()
        buttonsGroup("Apply to:") {
            row { cell(selectedOnly) }
            row { cell(all) }
        }
    }.apply { preferredSize = JBUI.size(560, 380) }

    override fun createLeftSideActions(): Array<Action> = arrayOf(object : AbstractAction("Open as Script") {
        override fun actionPerformed(e: ActionEvent) {
            if (doValidateAll().isNotEmpty()) return
            val file = LightVirtualFile("bulk-modify.ldif", LdifFileType, script())
            close(CANCEL_EXIT_CODE)
            OpenFileDescriptor(project, file).navigate(true)
        }
    })

    override fun getPreferredFocusedComponent(): JComponent = attribute

    private val dns: List<String> get() = if (selectedOnly.isSelected) selectedDns else allDns
    private val valueList: List<String> get() = values.text.lines().filter { it.isNotEmpty() }

    private fun script(): String {
        val op = operation.selectedItem as BulkOperation
        return BulkModify.script(
            dns, op, attribute.text.trim(), if (op.needsValues) valueList else emptyList(),
            listOf("Bulk modify on $connectionName: $op ${attribute.text.trim()} on ${dns.size} entries"),
        )
    }

    // Kotlin UI DSL dialogs don't re-validate by default, which would keep OK disabled after a fixed error.
    override fun continuousValidation() = true

    override fun doValidate(): ValidationInfo? {
        val op = operation.selectedItem as BulkOperation
        return when {
            attribute.text.isBlank() -> ValidationInfo("Enter an attribute name", attribute)
            !attribute.text.trim().matches(Regex("[A-Za-z0-9][A-Za-z0-9.;-]*")) -> ValidationInfo("Invalid attribute name", attribute)
            op.needsValues && valueList.isEmpty() -> ValidationInfo("Enter at least one value", values)
            dns.isEmpty() -> ValidationInfo("No entries to modify")
            else -> null
        }
    }

    override fun doOKAction() {
        val op = operation.selectedItem as BulkOperation
        val preview = ChangeRecords.modify(dns.first(), listOf(BulkModify.modification(op, attribute.text.trim(), valueList)))
        val confirmed = ChangePreviewDialog(
            project, "Bulk Modify", "This change will be applied to ${dns.size} entries on $connectionName. First entry:",
            preview, optional = false, okText = "Run on ${dns.size} Entries",
        ).showAndGet()
        if (!confirmed) return
        val text = script()
        super.doOKAction()
        LdifRunPanel.show(project, LdifSource.ofText("Bulk modify", text, null, 0), configId, connectionName, LdifRunOptions())
    }
}
