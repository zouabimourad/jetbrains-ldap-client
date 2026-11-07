package com.mouradzouabi.ldapclient.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

/** Multi-line text editor for attribute values. */
class TextValueDialog(
    project: Project,
    dialogTitle: String,
    initial: String,
    private val editable: Boolean,
    private val validator: (String) -> String? = { null },
) : DialogWrapper(project, true) {
    private val area = JBTextArea(initial, 12, 70).apply {
        lineWrap = true
        wrapStyleWord = true
        isEditable = editable
        font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size)
    }

    init {
        title = if (editable) dialogTitle else "$dialogTitle (read-only)"
        if (!editable) setOKButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent = JBScrollPane(area).apply { preferredSize = JBUI.size(640, 260) }

    override fun getPreferredFocusedComponent(): JComponent = area

    override fun doValidate(): ValidationInfo? = if (!editable) null else validator(text)?.let { ValidationInfo(it, area) }

    /** Trailing line breaks are almost always accidental in LDAP values. */
    val text: String get() = area.text.trimEnd('\n', '\r')
}
