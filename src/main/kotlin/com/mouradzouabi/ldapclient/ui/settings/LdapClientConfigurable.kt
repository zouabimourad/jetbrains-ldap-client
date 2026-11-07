package com.mouradzouabi.ldapclient.ui.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.mouradzouabi.ldapclient.model.LdapSettings
import com.mouradzouabi.ldapclient.service.LdapOperationLog

/** Settings | Tools | LDAP Client. */
class LdapClientConfigurable : BoundConfigurable("LDAP Client") {
    private val settings = LdapSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        group("Editing") {
            row {
                checkBox("Preview changes before saving entries")
                    .bindSelected(settings::previewChanges)
                    .comment("Shows the LDIF that will be sent to the server, with password values masked")
            }
            row {
                checkBox("Show operational attributes")
                    .bindSelected({ settings.showOperationalAttributes }, { settings.showOperationalAttributes = it })
            }
        }
        group("Search") {
            row("Default size limit:") {
                spinner(0..10_000_000, 100).bindIntValue(settings::defaultSearchSizeLimit)
                    .comment("0 = unlimited")
            }
        }
        group("Operation Log") {
            row {
                comment("The last ${LdapOperationLog.MAX_OPERATIONS} changes are kept in memory only, with password values masked.")
            }
        }
    }
}
