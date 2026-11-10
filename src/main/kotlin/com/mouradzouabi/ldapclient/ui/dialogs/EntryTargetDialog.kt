package com.mouradzouabi.ldapclient.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.mouradzouabi.ldapclient.core.ConflictPolicy
import com.mouradzouabi.ldapclient.core.LdapDn
import com.mouradzouabi.ldapclient.core.LdapDnMove
import com.mouradzouabi.ldapclient.model.LdapConnectionConfig
import com.mouradzouabi.ldapclient.model.LdapSettings
import javax.swing.JComponent

private fun connectionCombo(selectedId: String, writableOnly: Boolean = false): ComboBox<LdapConnectionConfig> {
    val configs = LdapSettings.getInstance().connections.filter { !writableOnly || !it.readOnly || it.id == selectedId }
    return ComboBox(configs.toTypedArray()).apply {
        renderer = textListCellRenderer { it.displayName + if (it.readOnly) " (read-only)" else "" }
        configs.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }?.let { selectedIndex = it }
    }
}

/** Chooses the entry to compare an entry with, on the same or another connection. */
class CompareWithDialog(project: Project, configId: String, private val dn: String) : DialogWrapper(project, true) {
    private val connection = connectionCombo(configId)
    private val dnField = JBTextField(dn, 50)

    val targetConfig: LdapConnectionConfig get() = connection.selectedItem as LdapConnectionConfig
    val targetDn: String get() = dnField.text.trim()

    init {
        title = "Compare Entry"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Entry:") { cell(com.intellij.ui.components.JBLabel(dn).setCopyable(true)) }
        row("Compare with:") { cell(connection).align(AlignX.FILL) }
        row("DN:") { cell(dnField).align(AlignX.FILL).comment("Compare the same DN on another server, or another entry") }
    }.apply { preferredSize = JBUI.size(600, preferredSize.height) }

    override fun getPreferredFocusedComponent(): JComponent = dnField

    // Kotlin UI DSL dialogs don't re-validate by default, which would keep OK disabled after a fixed error.
    override fun continuousValidation() = true

    override fun doValidate(): ValidationInfo? = when {
        connection.selectedItem == null -> ValidationInfo("Select a connection", connection)
        LdapDn.parse(targetDn) == null -> ValidationInfo("Invalid DN", dnField)
        else -> null
    }
}

/** Options of Copy / Move Entry. */
class CopyMoveDialog(project: Project, private val configId: String, private val dn: String, move: Boolean) : DialogWrapper(project, true) {
    private val connection = connectionCombo(configId, writableOnly = true)
    private val parentField = JBTextField(LdapDn.parent(dn).orEmpty(), 50)
    private val rdnField = JBTextField(LdapDn.rdn(dn), 30)
    private val subtree = JBCheckBox("Include the whole subtree", true)
    private val moveBox = JBCheckBox("Move (delete the source after a successful copy)", move)
    private val conflict = ComboBox(ConflictPolicy.entries.toTypedArray())
    private val stopOnError = JBCheckBox("Stop at the first error", true)

    val targetConfig: LdapConnectionConfig get() = connection.selectedItem as LdapConnectionConfig
    val targetDn: String get() = LdapDn.child(rdnField.text.trim(), parentField.text.trim())
    val recursive: Boolean get() = subtree.isSelected
    val isMove: Boolean get() = moveBox.isSelected
    val conflictPolicy: ConflictPolicy get() = conflict.selectedItem as ConflictPolicy
    val stopAtFirstError: Boolean get() = stopOnError.isSelected

    init {
        title = if (move) "Move Entry" else "Copy Entry"
        setOKButtonText(if (move) "Move" else "Copy")
        moveBox.addActionListener { setOKButtonText(if (moveBox.isSelected) "Move" else "Copy") }
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Source:") { cell(com.intellij.ui.components.JBLabel(dn).setCopyable(true)) }
        separator()
        row("Target connection:") { cell(connection).align(AlignX.FILL) }
        row("Target parent DN:") { cell(parentField).align(AlignX.FILL) }
        row("New RDN:") { cell(rdnField).align(AlignX.FILL) }
        row { cell(subtree) }
        row("If an entry exists:") { cell(conflict) }
        row { cell(stopOnError) }
        row { cell(moveBox).comment("Within the same connection a move is done with a single Modify DN request.") }
    }.apply { preferredSize = JBUI.size(620, preferredSize.height) }

    override fun getPreferredFocusedComponent(): JComponent = parentField

    // Kotlin UI DSL dialogs don't re-validate by default, which would keep OK disabled after a fixed error.
    override fun continuousValidation() = true

    override fun doValidate(): ValidationInfo? {
        if (connection.selectedItem == null) return ValidationInfo("Select a connection", connection)
        if (!LdapDn.isValidRdn(rdnField.text.trim())) return ValidationInfo("Invalid RDN", rdnField)
        if (LdapDn.parse(parentField.text.trim()) == null) return ValidationInfo("Invalid DN", parentField)
        if (targetConfig.id == configId) {
            if (LdapDnMove.isSameOrBelow(targetDn, dn)) return ValidationInfo("Cannot copy or move an entry into itself or its own subtree", parentField)
        }
        return null
    }
}
