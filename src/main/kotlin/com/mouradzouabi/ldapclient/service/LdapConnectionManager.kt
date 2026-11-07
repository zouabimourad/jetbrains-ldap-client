package com.mouradzouabi.ldapclient.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.net.ssl.CertificateManager
import com.unboundid.util.ssl.SSLUtil
import com.unboundid.util.ssl.TrustAllTrustManager
import com.mouradzouabi.ldapclient.core.LdapSession
import com.mouradzouabi.ldapclient.model.AuthMethod
import com.mouradzouabi.ldapclient.model.LdapConnectionConfig
import com.mouradzouabi.ldapclient.model.LdapCredentials
import com.mouradzouabi.ldapclient.model.LdapEvents
import com.mouradzouabi.ldapclient.model.LdapListener
import com.mouradzouabi.ldapclient.model.LdapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext

/**
 * Owns the connection pools (one per configured connection) shared by all projects.
 * Connecting happens on [Dispatchers.IO]; concurrent connect requests for the same connection are merged.
 */
@Service(Service.Level.APP)
class LdapConnectionManager(private val cs: CoroutineScope) : Disposable {
    private val sessions = ConcurrentHashMap<String, LdapSession>()
    private val pending = ConcurrentHashMap<String, Deferred<Result<LdapSession>>>()
    private val knownConfigs = ConcurrentHashMap<String, LdapConnectionConfig>()

    init {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(LdapListener.TOPIC, object : LdapListener {
            override fun connectionsChanged() = onConfigsChanged()
        })
        LdapSettings.getInstance().connections.forEach { knownConfigs[it.id] = it }
        cs.launch(Dispatchers.IO) {
            while (isActive) {
                delay(CURSOR_SWEEP_MILLIS)
                sessions.values.forEach { runCatching { it.releaseIdleCursors(CURSOR_IDLE_MILLIS) } }
            }
        }
    }

    fun session(configId: String): LdapSession? = sessions[configId]?.takeUnless { it.closed }

    fun isConnected(configId: String): Boolean = session(configId) != null

    fun isConnecting(configId: String): Boolean = pending.containsKey(configId)

    /** Returns the open session or connects (suspending, never blocks the calling thread). */
    suspend fun connect(configId: String): LdapSession {
        session(configId)?.let { return it }
        val deferred = pending.computeIfAbsent(configId) {
            cs.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    runCatching { open(configId) }.onSuccess { sessions[configId] = it }
                } finally {
                    pending.remove(configId)
                    LdapEvents.fire { it.connectionStateChanged(configId) }
                }
            }
        }
        LdapEvents.fire { it.connectionStateChanged(configId) }
        deferred.start()
        return deferred.await().getOrThrow()
    }

    fun disconnect(configId: String) {
        val session = sessions.remove(configId) ?: return
        cs.launch(Dispatchers.IO) { runCatching { session.close() } }
        LdapEvents.fire { it.connectionStateChanged(configId) }
    }

    /** Blocking: test a configuration without registering it. */
    fun testConnection(config: LdapConnectionConfig, password: String?): String {
        LdapSession.open(config, password, ::sslContext).use { session ->
            session.pool.getEntry(session.baseDns.first().ifEmpty { "" }, "1.1")
            return "${session.serverDescription}\nNaming contexts: ${session.baseDns.joinToString(", ")}"
        }
    }

    /** Blocking: lists naming contexts of a server. */
    fun fetchNamingContexts(config: LdapConnectionConfig, password: String?): List<String> =
        LdapSession.open(config.copy().apply { baseDn = "" }, password, ::sslContext).use { session ->
            session.rootDse?.namingContextDNs?.toList().orEmpty()
        }

    private fun open(configId: String): LdapSession {
        val config = LdapSettings.getInstance().find(configId) ?: error("Unknown LDAP connection $configId")
        val password = if (config.authMethod == AuthMethod.SIMPLE) LdapCredentials.getPassword(configId) else null
        return LdapSession.open(config, password, ::sslContext).also { it.operationListener = LdapOperationLog.getInstance() }
    }

    private fun sslContext(config: LdapConnectionConfig): SSLContext =
        if (config.trustAllCertificates) SSLUtil(TrustAllTrustManager()).createSSLContext()
        else CertificateManager.getInstance().sslContext

    private fun onConfigsChanged() {
        val current = LdapSettings.getInstance().connections.associateBy { it.id }
        for ((id, old) in knownConfigs) {
            val now = current[id]
            if (now == null || now.connectionSettingsDiffer(old)) disconnect(id)
        }
        knownConfigs.clear()
        knownConfigs.putAll(current)
    }

    override fun dispose() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
    }

    companion object {
        private const val CURSOR_SWEEP_MILLIS = 30_000L
        private const val CURSOR_IDLE_MILLIS = 120_000L

        fun getInstance(): LdapConnectionManager = service()
    }
}
