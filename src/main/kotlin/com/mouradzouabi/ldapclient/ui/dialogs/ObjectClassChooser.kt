package com.mouradzouabi.ldapclient.ui.dialogs

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CheckBoxList
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mouradzouabi.ldapclient.core.EntryTemplate
import com.mouradzouabi.ldapclient.core.LdapSchema
import com.unboundid.ldap.sdk.schema.ObjectClassType
import javax.swing.JCheckBox
import javax.swing.JComponent

/** Lets the user pick the object classes of a new entry (one structural class plus any auxiliary classes). */
class ObjectClassChooser(private val schema: LdapSchema) : DialogWrapper(true) {

    private val list = CheckBoxList<String>()
    private val summary = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        setAllowAutoWrapping(true)
    }

    val selected: List<String> get() = list.checkedItems

    init {
        title = "Choose Object Classes"
        val s = schema.schema
        for (name in EntryTemplate.structuralObjectClasses(schema)) {
            val def = s?.getObjectClass(name)
            val type = def?.getObjectClassType(s)?.getName()?.lowercase() ?: ""
            val description = def?.description?.let { " — $it" }.orEmpty()
            list.addItem(name, "$name  [$type]$description", false)
        }
        list.setCheckBoxListListener { _, _ -> updateSummary() }
        ListSpeedSearch.installOn(list) { (it as? JCheckBox)?.text?.substringBefore("  [") }
        updateSummary()
        init()
    }

    private fun updateSummary() {
        val chosen = selected
        summary.text = if (chosen.isEmpty()) "Type to search; check one structural class and optional auxiliary classes."
        else {
            val chain = EntryTemplate.objectClassChain(schema, chosen)
            "objectClass: ${chain.joinToString(", ")} — RDN: ${EntryTemplate.rdnAttribute(schema, chain)}"
        }
    }

    override fun createCenterPanel(): JComponent = panel {
        row { cell(JBScrollPane(list)).align(Align.FILL) }.resizableRow()
        row { cell(summary).align(Align.FILL) }
    }.apply { preferredSize = JBUI.size(520, 460) }

    override fun getPreferredFocusedComponent(): JComponent = list

    // Kotlin UI DSL dialogs don't re-validate by default, which would keep OK disabled after a fixed error.
    override fun continuousValidation() = true

    override fun doValidate(): ValidationInfo? {
        val s = schema.schema ?: return null
        val structural = selected.count { s.getObjectClass(it)?.getObjectClassType(s) == ObjectClassType.STRUCTURAL }
        return when {
            selected.isEmpty() -> ValidationInfo("Select at least one object class", list)
            structural == 0 -> ValidationInfo("No structural object class selected", list).asWarning().withOKEnabled()
            structural > 1 -> ValidationInfo("More than one structural object class selected", list).asWarning().withOKEnabled()
            else -> null
        }
    }
}
