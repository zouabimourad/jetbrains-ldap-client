package com.mouradzouabi.ldapclient.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.mouradzouabi.ldapclient.core.LdapSchema
import javax.swing.JComponent

/** Picks an attribute name, suggesting the attributes allowed by the entry's object classes. */
class AddAttributeDialog(
    project: Project,
    private val schema: LdapSchema,
    private val objectClasses: List<String>,
    private val existing: Set<String>,
) : DialogWrapper(project, true) {

    private val comboModel = CollectionComboBoxModel<String>()
    private val combo = ComboBox(comboModel).apply { isEditable = true }
    private val allBox = JBCheckBox("Show all schema attributes")

    init {
        title = "Add Attribute"
        allBox.addActionListener { fill() }
        allBox.isEnabled = schema.schema != null
        fill()
        init()
    }

    private fun fill() {
        val candidates = if (allBox.isSelected || schema.allowedAttributes(objectClasses).isEmpty()) schema.attributeNames
        else schema.allowedAttributes(objectClasses).toList()
        val required = schema.requiredAttributes(objectClasses)
        val missingRequired = required.filter { r -> existing.none { it.equals(r, true) } }
        val items = (missingRequired + candidates.filter { c -> existing.none { it.equals(c, true) } && !schema.isOperational(c) })
            .distinctBy { it.lowercase() }
        comboModel.replaceAll(items)
        if (items.isNotEmpty()) combo.selectedIndex = 0
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Attribute:") {
            cell(combo).align(AlignX.FILL).focused().comment(
                if (schema.schema == null) "Server schema unavailable — type the attribute name"
                else "Missing required attributes are listed first",
            )
        }
        row { cell(allBox) }
    }

    val attributeName: String get() = (combo.editor.item as? String ?: combo.selectedItem as? String).orEmpty().trim()

    override fun doValidate(): ValidationInfo? {
        val name = attributeName
        if (!Regex("^([A-Za-z][A-Za-z0-9-]*|[0-9]+(\\.[0-9]+)*)(;[A-Za-z0-9-]+)*$").matches(name)) {
            return ValidationInfo("Invalid attribute name", combo)
        }
        return null
    }
}
