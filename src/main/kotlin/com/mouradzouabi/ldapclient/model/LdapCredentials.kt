package com.mouradzouabi.ldapclient.model

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Bind passwords are kept in the IDE password safe. Access to the password safe may block
 * (e.g. OS keychain), so reads must happen on a background thread.
 */
object LdapCredentials {
    private val cache = ConcurrentHashMap<String, Optional<String>>()

    private fun attributes(configId: String) =
        CredentialAttributes(generateServiceName("LDAP Client", configId))

    /** Blocking: never call on the EDT. */
    fun getPassword(configId: String): String? {
        cache[configId]?.let { return it.orElse(null) }
        val password = PasswordSafe.instance.getPassword(attributes(configId))
        cache[configId] = Optional.ofNullable(password)
        return password
    }

    /** Updates the in-memory value immediately and persists asynchronously. */
    fun setPassword(configId: String, password: String?) {
        cache[configId] = Optional.ofNullable(password)
        ApplicationManager.getApplication().executeOnPooledThread {
            PasswordSafe.instance.setPassword(attributes(configId), password)
        }
    }
}
