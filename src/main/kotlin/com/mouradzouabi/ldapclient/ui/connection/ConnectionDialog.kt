package com.mouradzouabi.ldapclient.ui.connection

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.icons.AllIcons
import com.unboundid.ldap.sdk.DN
import com.mouradzouabi.ldapclient.core.LdapErrors
import com.mouradzouabi.ldapclient.model.AuthMethod
import com.mouradzouabi.ldapclient.model.LdapConnectionConfig
import com.mouradzouabi.ldapclient.model.LdapCredentials
import com.mouradzouabi.ldapclient.model.SecurityMode
import com.mouradzouabi.ldapclient.service.LdapConnectionManager
import com.mouradzouabi.ldapclient.service.LdapProjectService
import com.mouradzouabi.ldapclient.ui.UiDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.ActionEvent
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import kotlin.coroutines.cancellation.CancellationException

class ConnectionDialog(
    project: Project,
    private val config: LdapConnectionConfig,
    private val isNew: Boolean,
) : DialogWrapper(project, true) {
    private val scope = LdapProjectService.getInstance(project).childScope(disposable)

    private val nameField = JBTextField(config.name)
    private val hostField = JBTextField(config.host)
    private val portField = JBIntSpinner(config.port, 1, 65535)
    private val securityCombo = ComboBox(SecurityMode.entries.toTypedArray()).apply { selectedItem = config.security }
    private val verifyHostBox = JBCheckBox("Verify host name", config.verifyHostname)
    private val trustAllBox = JBCheckBox("Trust all certificates (insecure)", config.trustAllCertificates)
    private val authCombo = ComboBox(AuthMethod.entries.toTypedArray()).apply { selectedItem = config.authMethod }
    private val bindDnField = JBTextField(config.bindDn)
    private val passwordField = JBPasswordField()
    private val baseDnField = JBTextField(config.baseDn)
    private val pageSizeSpinner = JBIntSpinner(config.pageSize, 10, 10_000)
    private val connectTimeoutSpinner = JBIntSpinner(config.connectTimeoutSeconds, 1, 600)
    private val responseTimeoutSpinner = JBIntSpinner(config.responseTimeoutSeconds, 1, 3600)
    private val maxConnectionsSpinner = JBIntSpinner(config.maxConnections, 1, 50)
    private val readOnlyBox = JBCheckBox("Read-only (prevent modifications)", config.readOnly)
    private val followReferralsBox = JBCheckBox("Follow referrals", config.followReferrals)
    private val testResult = JBLabel()
    private var testJob: Job? = null

    private var passwordTouched = isNew
    private var settingPassword = false

    init {
        title = if (isNew) "Add LDAP Connection" else "Edit LDAP Connection"
        passwordField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!settingPassword) passwordTouched = true
            }
        })
        securityCombo.addActionListener {
            val mode = securityCombo.selectedItem as SecurityMode
            if (mode == SecurityMode.LDAPS && portField.number == 389) portField.number = 636
            if (mode != SecurityMode.LDAPS && portField.number == 636) portField.number = 389
            updateEnablement()
        }
        authCombo.addActionListener { updateEnablement() }
        trustAllBox.addActionListener { updateEnablement() }
        updateEnablement()
        init()
        if (!isNew) loadStoredPassword()
    }

    /** The password safe may block, so the stored password is loaded in the background. */
    private fun loadStoredPassword() {
        passwordField.emptyText.text = "Loading…"
        scope.launch(Dispatchers.IO) {
            val stored = runCatching { LdapCredentials.getPassword(config.id) }.getOrNull()
            withContext(UiDispatcher) {
                passwordField.emptyText.text = ""
                if (!passwordTouched && stored != null) {
                    settingPassword = true
                    passwordField.text = stored
                    settingPassword = false
                }
            }
        }
    }

    private fun updateEnablement() {
        val secure = securityCombo.selectedItem != SecurityMode.NONE
        trustAllBox.isEnabled = secure
        verifyHostBox.isEnabled = secure && !trustAllBox.isSelected
        val simple = authCombo.selectedItem == AuthMethod.SIMPLE
        bindDnField.isEnabled = simple
        passwordField.isEnabled = simple
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") { cell(nameField).align(AlignX.FILL).focused() }
        row("Host:") {
            cell(hostField).align(AlignX.FILL).resizableColumn()
            label("Port:")
            cell(portField)
        }
        row("Security:") {
            cell(securityCombo)
            cell(verifyHostBox)
            cell(trustAllBox)
        }
        group("Authentication") {
            row("Method:") { cell(authCombo) }
            row("Bind DN:") { cell(bindDnField).align(AlignX.FILL).comment("e.g. cn=admin,dc=example,dc=com") }
            row("Password:") { cell(passwordField).align(AlignX.FILL).comment("Stored in the IDE password safe") }
        }
        group("Browsing") {
            row("Base DN:") {
                cell(baseDnField).align(AlignX.FILL).resizableColumn()
                    .comment("Optional, separate several DNs with |. Empty: use the server naming contexts")
                button("Fetch…") { fetchBaseDns(it) }
            }
            row("Page size:") {
                cell(pageSizeSpinner).comment("Entries loaded per page in the tree and per paged search request")
            }
            row {
                cell(readOnlyBox)
                cell(followReferralsBox)
            }
        }
        collapsibleGroup("Advanced") {
            row("Connect timeout (s):") { cell(connectTimeoutSpinner) }
            row("Response timeout (s):") { cell(responseTimeoutSpinner) }
            row("Max pooled connections:") { cell(maxConnectionsSpinner) }
        }
        row {
            button("Test Connection") { testConnection() }
            cell(testResult).align(AlignX.FILL).resizableColumn()
        }
    }

    val result: LdapConnectionConfig
        get() = config.copy().apply {
            name = nameField.text.trim()
            host = hostField.text.trim()
            port = portField.number
            security = securityCombo.selectedItem as SecurityMode
            verifyHostname = verifyHostBox.isSelected
            trustAllCertificates = trustAllBox.isSelected
            authMethod = authCombo.selectedItem as AuthMethod
            bindDn = bindDnField.text.trim()
            baseDn = baseDnField.text.trim()
            pageSize = pageSizeSpinner.number
            connectTimeoutSeconds = connectTimeoutSpinner.number
            responseTimeoutSeconds = responseTimeoutSpinner.number
            maxConnections = maxConnectionsSpinner.number
            readOnly = readOnlyBox.isSelected
            followReferrals = followReferralsBox.isSelected
        }

    val password: String? get() = String(passwordField.password).ifEmpty { null }

    /** Non-null only when the user typed a new password (empty string clears it). */
    val passwordIfChanged: String? get() = if (passwordTouched) String(passwordField.password) else null

    private fun testConnection() {
        val candidate = result
        val pwd = password
        testJob?.cancel()
        testResult.icon = com.intellij.ui.AnimatedIcon.Default.INSTANCE
        testResult.text = "Connecting…"
        testJob = scope.launch(Dispatchers.IO) {
            val message = try {
                Result.success(LdapConnectionManager.getInstance().testConnection(candidate, pwd))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            withContext(UiDispatcher) {
                message.onSuccess {
                    testResult.icon = AllIcons.General.InspectionsOK
                    testResult.text = "<html>Success: ${it.replace("\n", "<br>")}</html>"
                }.onFailure {
                    testResult.icon = AllIcons.General.Error
                    testResult.text = "<html>${com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(LdapErrors.describe(it))}</html>"
                }
            }
        }
    }

    private fun fetchBaseDns(event: ActionEvent) {
        val anchor = event.source as? JComponent ?: return
        val candidate = result
        val pwd = password
        anchor.isEnabled = false
        scope.launch(Dispatchers.IO) {
            val contexts = try {
                Result.success(LdapConnectionManager.getInstance().fetchNamingContexts(candidate, pwd))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            withContext(UiDispatcher) {
                anchor.isEnabled = true
                contexts.onSuccess { list ->
                    if (list.isEmpty()) {
                        testResult.icon = AllIcons.General.Warning
                        testResult.text = "The server does not publish naming contexts"
                    } else {
                        JBPopupFactory.getInstance().createPopupChooserBuilder(list)
                            .setTitle("Naming Contexts")
                            .setItemChosenCallback { baseDnField.text = it }
                            .createPopup().showUnderneathOf(anchor)
                    }
                }.onFailure {
                    testResult.icon = AllIcons.General.Error
                    testResult.text = LdapErrors.describe(it)
                }
            }
        }
    }

    // Kotlin UI DSL dialogs don't re-validate by default, which would keep OK disabled after a fixed error.
    override fun continuousValidation() = true

    override fun doValidate(): ValidationInfo? {
        if (hostField.text.isBlank()) return ValidationInfo("Host is required", hostField)
        if (authCombo.selectedItem == AuthMethod.SIMPLE && bindDnField.text.isNotBlank() && !DN.isValidDN(bindDnField.text.trim())) {
            return ValidationInfo("Invalid bind DN", bindDnField)
        }
        val invalidBase = result.baseDnList.firstOrNull { !DN.isValidDN(it) }
        if (invalidBase != null) return ValidationInfo("Invalid base DN: $invalidBase", baseDnField)
        return null
    }
}
