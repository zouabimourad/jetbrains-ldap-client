package com.mouradzouabi.ldapclient.ui.dialogs

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.mouradzouabi.ldapclient.core.AttributeKind
import com.mouradzouabi.ldapclient.core.AttributeKinds
import com.mouradzouabi.ldapclient.core.PasswordHasher
import com.mouradzouabi.ldapclient.core.PasswordScheme
import java.security.SecureRandom
import javax.swing.JComponent

/**
 * Password editor: hashes the new password client side with the selected scheme (or sends it in plain
 * text / AD unicodePwd format) and can verify a candidate password against the current hash.
 */
class PasswordValueDialog(project: Project, private val attribute: String, private val current: ByteArray?) :
    DialogWrapper(project, true) {

    private val isAd = attribute.equals("unicodePwd", ignoreCase = true)
    private val newField = JBPasswordField()
    private val confirmField = JBPasswordField()
    private val showBox = JBCheckBox("Show password")
    private val schemeCombo = ComboBox(
        if (isAd) arrayOf(PasswordScheme.AD_UNICODE_PWD) else PasswordScheme.entries.filter { it != PasswordScheme.AD_UNICODE_PWD }.toTypedArray(),
    )
    private val verifyField = JBPasswordField()
    private val verifyResult = JBLabel()
    private val echoChar = newField.echoChar

    init {
        title = "Set $attribute"
        val currentScheme = current?.let { Regex("^\\{([^}]+)}").find(String(it, Charsets.UTF_8))?.groupValues?.get(1) }
        schemeCombo.selectedItem = when {
            isAd -> PasswordScheme.AD_UNICODE_PWD
            currentScheme != null -> PasswordScheme.ofPrefix(currentScheme) ?: PasswordScheme.SSHA
            current != null -> PasswordScheme.PLAIN
            else -> PasswordScheme.SSHA
        }
        showBox.addActionListener {
            val c = if (showBox.isSelected) 0.toChar() else echoChar
            newField.echoChar = c
            confirmField.echoChar = c
        }
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        if (current != null) {
            row("Current value:") { label(AttributeKinds.display(AttributeKind.PASSWORD, current)) }
            row("Check password:") {
                cell(verifyField).align(AlignX.FILL).resizableColumn()
                button("Verify") { verify() }
            }
            row("") { cell(verifyResult) }
            separator()
        }
        row("New password:") { cell(newField).align(AlignX.FILL).focused() }
        row("Confirm:") { cell(confirmField).align(AlignX.FILL) }
        row("") {
            cell(showBox)
            button("Generate") { generate() }
        }
        row("Storage scheme:") {
            cell(schemeCombo).comment(
                if (isAd) "Active Directory requires an encrypted (LDAPS/StartTLS) connection to set unicodePwd"
                else "Hashing happens locally; choose plain text if the server applies its own password policy hashing",
            )
        }
    }

    private fun verify() {
        val stored = current ?: return
        val candidate = String(verifyField.password)
        when (PasswordHasher.verify(stored, candidate)) {
            true -> { verifyResult.icon = AllIcons.General.InspectionsOK; verifyResult.text = "Password matches" }
            false -> { verifyResult.icon = AllIcons.General.Error; verifyResult.text = "Password does not match" }
            null -> { verifyResult.icon = AllIcons.General.Warning; verifyResult.text = "This scheme cannot be verified locally" }
        }
    }

    private fun generate() {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!#%+-_=?"
        val random = SecureRandom()
        val password = (1..20).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        newField.text = password
        confirmField.text = password
        showBox.isSelected = true
        newField.echoChar = 0.toChar()
        confirmField.echoChar = 0.toChar()
    }

    // Kotlin UI DSL dialogs don't re-validate by default, which would keep OK disabled after a fixed error.
    override fun continuousValidation() = true

    override fun doValidate(): ValidationInfo? {
        if (newField.password.isEmpty()) return ValidationInfo("Enter a password", newField)
        if (!newField.password.contentEquals(confirmField.password)) return ValidationInfo("Passwords do not match", confirmField)
        return null
    }

    val value: ByteArray
        get() = PasswordHasher.encode(String(newField.password), schemeCombo.selectedItem as PasswordScheme)
}
