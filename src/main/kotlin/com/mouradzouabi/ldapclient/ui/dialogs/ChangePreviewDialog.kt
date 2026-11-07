package com.mouradzouabi.ldapclient.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.mouradzouabi.ldapclient.model.LdapSettings
import com.mouradzouabi.ldapclient.ui.LdifViewer
import javax.swing.JComponent

/**
 * Shows the LDIF change records about to be sent (password values masked) and asks for confirmation.
 * The "always preview" checkbox is only shown for previews that can be disabled.
 */
class ChangePreviewDialog(
    project: Project,
    title: String,
    private val description: String,
    ldif: String,
    private val optional: Boolean = true,
    okText: String = "Apply",
) : DialogWrapper(project, true) {
    private val viewer = LdifViewer(project, disposable, ldif)
    private val alwaysPreview = JBCheckBox("Preview changes before saving entries", LdapSettings.getInstance().previewChanges)

    init {
        this.title = title
        setOKButtonText(okText)
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label(description) }
        row { cell(viewer.component).align(Align.FILL) }.resizableRow()
        if (optional) row { cell(alwaysPreview).comment("Can be changed later in Settings | Tools | LDAP Client") }
    }.apply { preferredSize = JBUI.size(720, 420) }

    override fun doOKAction() {
        if (optional) LdapSettings.getInstance().previewChanges = alwaysPreview.isSelected
        super.doOKAction()
    }

    override fun getDimensionServiceKey() = "LdapClient.ChangePreview"

    companion object {
        /** Returns true when the change should be applied: either previews are disabled or the user confirmed. */
        fun confirm(project: Project, title: String, description: String, ldif: () -> String): Boolean {
            if (!LdapSettings.getInstance().previewChanges) return true
            return ChangePreviewDialog(project, title, description, ldif()).showAndGet()
        }
    }
}
