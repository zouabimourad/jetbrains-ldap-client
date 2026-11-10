package com.mouradzouabi.ldapclient.ui.ldif

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.mouradzouabi.ldapclient.core.ContentRecordMode
import com.mouradzouabi.ldapclient.core.LdifRunOptions
import com.mouradzouabi.ldapclient.model.LdapConnectionConfig
import com.mouradzouabi.ldapclient.model.LdapSettings
import javax.swing.JComponent

/** Chooses the target connection and execution options for an LDIF script. */
class LdifRunDialog(project: Project, private val sourceTitle: String, preferredConfigId: String?) : DialogWrapper(project, true) {
    private val properties = PropertiesComponent.getInstance()
    private val connections = LdapSettings.getInstance().connections
    private val connectionCombo = ComboBox(connections.toTypedArray()).apply {
        renderer = textListCellRenderer { it.displayName + if (it.readOnly) " (read-only)" else "" }
        val wanted = preferredConfigId ?: properties.getValue(LAST_CONNECTION)
        connections.indexOfFirst { it.id == wanted }.takeIf { it >= 0 }?.let { selectedIndex = it }
    }
    private val modeCombo = ComboBox(ContentRecordMode.entries.toTypedArray()).apply {
        selectedItem = ContentRecordMode.entries.firstOrNull { it.name == properties.getValue(LAST_MODE) } ?: ContentRecordMode.ADD
    }
    private val continueBox = JBCheckBox("Continue after errors", properties.getBoolean(LAST_CONTINUE, true))
    private val dryRunBox = JBCheckBox("Validate only (parse records, send nothing to the server)")

    init {
        title = "Run LDIF Script"
        setOKButtonText("Run")
        dryRunBox.addActionListener { setOKButtonText(if (dryRunBox.isSelected) "Validate" else "Run") }
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Script:") { label(sourceTitle) }
        row("Connection:") { cell(connectionCombo).align(AlignX.FILL).focused() }
        row("Records without changetype:") { cell(modeCombo) }
        row { cell(continueBox) }
        row { cell(dryRunBox) }
    }

    // Kotlin UI DSL dialogs don't re-validate by default, which would keep OK disabled after a fixed error.
    override fun continuousValidation() = true

    override fun doValidate(): ValidationInfo? {
        val config = connectionCombo.selectedItem as? LdapConnectionConfig
            ?: return ValidationInfo("Select a connection", connectionCombo)
        if (config.readOnly && !dryRunBox.isSelected) return ValidationInfo("Connection '${config.displayName}' is read-only", connectionCombo)
        return null
    }

    override fun doOKAction() {
        properties.setValue(LAST_CONNECTION, config.id)
        properties.setValue(LAST_MODE, options.contentMode.name)
        properties.setValue(LAST_CONTINUE, options.continueOnError, true)
        super.doOKAction()
    }

    val config: LdapConnectionConfig get() = connectionCombo.selectedItem as LdapConnectionConfig

    val options: LdifRunOptions
        get() = LdifRunOptions(
            contentMode = modeCombo.selectedItem as ContentRecordMode,
            continueOnError = continueBox.isSelected,
            dryRun = dryRunBox.isSelected,
        )

    private companion object {
        const val LAST_CONNECTION = "ldap.client.ldif.connection"
        const val LAST_MODE = "ldap.client.ldif.contentMode"
        const val LAST_CONTINUE = "ldap.client.ldif.continueOnError"
    }
}
