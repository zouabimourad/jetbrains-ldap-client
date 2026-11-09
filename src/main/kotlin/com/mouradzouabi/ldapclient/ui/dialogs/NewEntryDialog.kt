package com.mouradzouabi.ldapclient.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.unboundid.ldap.sdk.Entry
import com.unboundid.ldif.LDIFException
import com.unboundid.ldif.LDIFReader
import com.mouradzouabi.ldapclient.core.EntryTemplate
import com.mouradzouabi.ldapclient.core.LdapSchema
import javax.swing.JComponent

/**
 * Creates a child entry from an editable LDIF template. Templates are either predefined, generated from object classes
 * chosen in the schema, or [initialLdif] (used by Duplicate Entry).
 */
class NewEntryDialog(
    project: Project,
    private val parentDn: String,
    private val schema: LdapSchema,
    private val initialLdif: String? = null,
    dialogTitle: String? = null,
) : DialogWrapper(project, true) {

    private val templates = linkedMapOf(
        "Organizational unit" to "dn: ou=NewUnit,$parentDn\nobjectClass: top\nobjectClass: organizationalUnit\nou: NewUnit\n",
        "Person (inetOrgPerson)" to "dn: uid=jdoe,$parentDn\nobjectClass: top\nobjectClass: person\nobjectClass: organizationalPerson\nobjectClass: inetOrgPerson\nuid: jdoe\ncn: John Doe\nsn: Doe\ngivenName: John\nmail: jdoe@example.com\n",
        "POSIX account" to "dn: uid=jdoe,$parentDn\nobjectClass: top\nobjectClass: inetOrgPerson\nobjectClass: posixAccount\nuid: jdoe\ncn: John Doe\nsn: Doe\nuidNumber: 10000\ngidNumber: 10000\nhomeDirectory: /home/jdoe\nloginShell: /bin/bash\n",
        "Group (groupOfNames)" to "dn: cn=NewGroup,$parentDn\nobjectClass: top\nobjectClass: groupOfNames\ncn: NewGroup\n# groupOfNames requires at least one member\nmember: uid=someone,$parentDn\n",
        "POSIX group" to "dn: cn=newgroup,$parentDn\nobjectClass: top\nobjectClass: posixGroup\ncn: newgroup\ngidNumber: 10000\n",
        "Custom" to "dn: cn=NewEntry,$parentDn\nobjectClass: top\n",
    )
    private val templateCombo = ComboBox(templates.keys.toTypedArray())
    private val ldifArea = JBTextArea(14, 70).apply { font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size) }

    init {
        title = dialogTitle ?: "New Entry under ${parentDn.ifEmpty { "Root" }}"
        templateCombo.addActionListener { applyTemplate() }
        if (initialLdif != null) {
            ldifArea.text = initialLdif
            ldifArea.caretPosition = 0
        } else {
            applyTemplate()
        }
        init()
    }

    private fun chooseObjectClasses() {
        val chooser = ObjectClassChooser(schema)
        if (!chooser.showAndGet() || chooser.selected.isEmpty()) return
        ldifArea.text = EntryTemplate.generate(schema, chooser.selected, parentDn)
        ldifArea.caretPosition = 0
        ldifArea.requestFocusInWindow()
        val placeholder = ldifArea.text.indexOf(EntryTemplate.PLACEHOLDER)
        if (placeholder >= 0) ldifArea.select(placeholder, placeholder + EntryTemplate.PLACEHOLDER.length)
    }

    private fun applyTemplate() {
        val template = templates[templateCombo.selectedItem as String].orEmpty()
        ldifArea.text = template
        ldifArea.caretPosition = 0
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Template:") {
            cell(templateCombo)
            button("From Object Classes…") { chooseObjectClasses() }.enabled(schema.schema != null)
                .applyToComponent { toolTipText = "Generate a template with the attributes required and allowed by object classes of the schema" }
        }
        row {
            cell(JBScrollPane(ldifArea)).align(Align.FILL).comment(requiredComment())
        }.resizableRow()
    }.apply { preferredSize = JBUI.size(700, 420) }

    private fun requiredComment(): String =
        if (schema.schema == null) "Edit the LDIF. The first line must be the DN."
        else "Edit the LDIF. Fill in empty values, uncomment optional attributes as needed. Lines starting with # are ignored."

    override fun getPreferredFocusedComponent(): JComponent = ldifArea

    private fun parse(): Entry = LDIFReader.decodeEntry(
        *ldifArea.text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.toTypedArray(),
    )

    // Kotlin UI DSL dialogs don't re-validate by default, which would keep OK disabled after a fixed error.
    override fun continuousValidation() = true

    override fun doValidate(): ValidationInfo? = try {
        val entry = parse()
        val empty = EntryTemplate.emptyAttributes(entry)
        if (entry.objectClassValues.isNullOrEmpty()) ValidationInfo("At least one objectClass is required", ldifArea)
        else if (empty.isNotEmpty()) ValidationInfo("Empty values for: ${empty.joinToString()}", ldifArea)
        else if (EntryTemplate.PLACEHOLDER in entry.dn) ValidationInfo("Replace '${EntryTemplate.PLACEHOLDER}' in the DN and RDN attribute", ldifArea)
        else {
            val missing = schema.requiredAttributes(entry.objectClassValues.toList()).filter { !entry.hasAttribute(it) }
            if (missing.isNotEmpty()) ValidationInfo("Missing required attributes: ${missing.joinToString()}", ldifArea).asWarning().withOKEnabled()
            else null
        }
    } catch (e: LDIFException) {
        ValidationInfo("Invalid LDIF: ${e.exceptionMessage}", ldifArea)
    }

    val entry: Entry get() = parse()
}
