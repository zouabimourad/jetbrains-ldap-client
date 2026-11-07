package com.mouradzouabi.ldapclient.core

import com.unboundid.asn1.ASN1OctetString
import com.unboundid.ldap.sdk.Control
import com.unboundid.ldap.sdk.DeleteRequest
import com.unboundid.ldap.sdk.Entry
import com.unboundid.ldap.sdk.LDAPConnection
import com.unboundid.ldap.sdk.LDAPConnectionOptions
import com.unboundid.ldap.sdk.LDAPConnectionPool
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.LDAPSearchException
import com.unboundid.ldap.sdk.ModifyDNRequest
import com.unboundid.ldap.sdk.ModifyRequest
import com.unboundid.ldap.sdk.Modification
import com.unboundid.ldap.sdk.ResultCode
import com.unboundid.ldap.sdk.RootDSE
import com.unboundid.ldap.sdk.SearchResultEntry
import com.unboundid.ldap.sdk.SearchScope
import com.unboundid.ldap.sdk.SimpleBindRequest
import com.unboundid.ldap.sdk.SingleServerSet
import com.unboundid.ldap.sdk.StartTLSPostConnectProcessor
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl
import com.unboundid.ldap.sdk.controls.SubtreeDeleteRequestControl
import com.unboundid.ldap.sdk.Filter
import com.unboundid.ldif.LDIFAddChangeRecord
import com.unboundid.ldif.LDIFChangeRecord
import com.unboundid.ldif.LDIFDeleteChangeRecord
import com.unboundid.ldif.LDIFModifyDNChangeRecord
import com.unboundid.util.ssl.HostNameSSLSocketVerifier
import com.mouradzouabi.ldapclient.model.AuthMethod
import com.mouradzouabi.ldapclient.model.LdapConnectionConfig
import com.mouradzouabi.ldapclient.model.SecurityMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import kotlin.coroutines.cancellation.CancellationException

data class SearchOutcome(val count: Int, val truncated: Boolean)

/**
 * An open connection pool to a directory server plus server capabilities.
 * All methods doing network I/O are either `suspend` (and switch to [Dispatchers.IO]) or documented as blocking.
 */
class LdapSession(
    val config: LdapConnectionConfig,
    val pool: LDAPConnectionPool,
    val rootDse: RootDSE?,
) : AutoCloseable {

    /** Non-critical paged control is always sent unless the server explicitly does not advertise it. */
    val supportsPaging: Boolean = rootDse?.supportsControl(SimplePagedResultsControl.PAGED_RESULTS_OID) ?: true
    val supportsSubtreeDelete: Boolean =
        rootDse?.supportsControl(SubtreeDeleteRequestControl.SUBTREE_DELETE_REQUEST_OID) ?: false

    val serverDescription: String
        get() = listOfNotNull(rootDse?.vendorName, rootDse?.vendorVersion).joinToString(" ").ifBlank { "LDAP server" }

    val pageSize: Int get() = config.pageSize.coerceIn(10, 10_000)

    val baseDns: List<String>
        get() = config.baseDnList.ifEmpty {
            rootDse?.namingContextDNs?.filter { it.isNotBlank() }?.toList().orEmpty()
        }.ifEmpty { listOf("") }

    private val cursors: MutableSet<PagedSearch> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var schema: LdapSchema? = null

    @Volatile
    var closed = false
        private set

    /** Notified after every write operation sent to the server (successful or not). */
    @Volatile
    var operationListener: LdapOperationListener? = null

    /** Blocking. Loads and caches the server schema (empty schema when unreadable). */
    fun schema(): LdapSchema {
        schema?.let { return it }
        val loaded = LdapSchema(runCatching { pool.schema }.getOrNull())
        schema = loaded
        return loaded
    }

    suspend fun schemaAsync(): LdapSchema = withContext(Dispatchers.IO) { schema() }

    /** Forgets the cached schema so that the next [schema] call reads it again. */
    fun invalidateSchema() {
        schema = null
    }

    fun cursor(baseDn: String, scope: SearchScope, filter: Filter, attributes: List<String>): PagedSearch =
        PagedSearch(this, SearchSpec(baseDn, scope, filter, attributes, timeLimitSeconds = config.responseTimeoutSeconds), pageSize)

    internal fun register(cursor: PagedSearch) { cursors += cursor }
    internal fun unregister(cursor: PagedSearch) { cursors -= cursor }

    /** Returns connections held by idle tree cursors to the pool. Blocking; call from a background thread. */
    fun releaseIdleCursors(maxIdleMillis: Long) {
        val now = System.currentTimeMillis()
        cursors.filter { now - it.lastUsed > maxIdleMillis }.forEach { it.releaseIfIdle() }
    }

    suspend fun getEntry(dn: String, attributes: List<String>): SearchResultEntry? = withContext(Dispatchers.IO) {
        pool.getEntry(dn, *attributes.toTypedArray())
    }

    suspend fun modify(dn: String, modifications: List<Modification>, controls: List<Control> = emptyList()) = withContext(Dispatchers.IO) {
        checkWritable()
        logged("modify", dn, { ChangeRecords.modify(dn, modifications) }) {
            pool.modify(ModifyRequest(dn, modifications, controls.toTypedArray()))
        }
    }

    suspend fun add(entry: Entry) = withContext(Dispatchers.IO) {
        checkWritable()
        logged("add", entry.dn, { ChangeRecords.toLdif(LDIFAddChangeRecord(entry)) }) { pool.add(entry) }
    }

    /** Applies an LDIF change record (add / modify / delete / moddn), including its controls. */
    suspend fun applyChange(record: LDIFChangeRecord) = withContext(Dispatchers.IO) {
        checkWritable()
        logged(operationName(record), record.dn, { ChangeRecords.toLdif(record) }) { record.processChange(pool, true) }
    }

    suspend fun rename(dn: String, newRdn: String, deleteOldRdn: Boolean = true) = move(dn, newRdn, null, deleteOldRdn)

    /** Renames [dn] and/or moves it below [newSuperior] (null keeps the parent). */
    suspend fun move(dn: String, newRdn: String, newSuperior: String?, deleteOldRdn: Boolean = true) = withContext(Dispatchers.IO) {
        checkWritable()
        val record = LDIFModifyDNChangeRecord(dn, newRdn, deleteOldRdn, newSuperior)
        logged("moddn", dn, { ChangeRecords.toLdif(record) }) { pool.modifyDN(ModifyDNRequest(dn, newRdn, deleteOldRdn, newSuperior)) }
    }

    /**
     * Deletes [dn]. When [recursive] is set, uses the subtree delete control if available, otherwise
     * deletes all descendants client side (deepest first). [onProgress] receives (deleted, total).
     */
    suspend fun delete(dn: String, recursive: Boolean, onProgress: (Int, Int) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.IO) {
            checkWritable()
            if (!recursive) {
                deleteLogged(dn)
                return@withContext
            }
            if (supportsSubtreeDelete) {
                val control = SubtreeDeleteRequestControl(true)
                logged("delete", dn, { ChangeRecords.toLdif(LDIFDeleteChangeRecord(dn, listOf<Control>(control))) }) {
                    pool.delete(DeleteRequest(dn).apply { addControl(control) })
                }
                return@withContext
            }
            val dns = mutableListOf<String>()
            search(SearchSpec(dn, SearchScope.SUB, Filter.createPresenceFilter("objectClass"), listOf("1.1")), 0) {
                synchronized(dns) { dns += it.dn }
            }
            val ordered = dns.sortedByDescending { LdapDn.depth(it) }
            ordered.forEachIndexed { index, child ->
                ensureActive()
                deleteLogged(child)
                onProgress(index + 1, ordered.size)
            }
        }

    /**
     * Streams all results of [spec] (using paged results when available) to [onEntry].
     * At most [maxResults] entries are delivered (0 = unlimited).
     */
    suspend fun search(spec: SearchSpec, maxResults: Int, onEntry: (SearchResultEntry) -> Unit): SearchOutcome =
        withContext(Dispatchers.IO) {
            val connection = pool.connection
            var failure: Throwable? = null
            try {
                var delivered = 0
                var truncated = false
                var cookie: ASN1OctetString? = null
                do {
                    val remaining = if (maxResults > 0) maxResults - delivered else Int.MAX_VALUE
                    val controls = mutableListOf<Control>()
                    val effectiveSpec = if (supportsPaging) {
                        controls += SimplePagedResultsControl(minOf(pageSize, remaining), cookie, false)
                        spec
                    } else {
                        spec.copy(sizeLimit = if (maxResults > 0) maxResults else spec.sizeLimit)
                    }
                    val result = connection.searchSuspending(effectiveSpec, controls) { entry ->
                        if (maxResults <= 0 || delivered < maxResults) {
                            delivered++
                            onEntry(entry)
                        } else {
                            truncated = true
                        }
                    }
                    when (result.resultCode) {
                        ResultCode.SUCCESS -> {}
                        ResultCode.SIZE_LIMIT_EXCEEDED -> truncated = true
                        else -> throw LDAPSearchException(result)
                    }
                    val paged = SimplePagedResultsControl.get(result)
                    cookie = if (paged != null && paged.moreResultsToReturn()) paged.cookie else null
                    if (cookie != null && maxResults > 0 && delivered >= maxResults) {
                        truncated = true
                        abandonPagedSearch(connection, effectiveSpec, cookie)
                        cookie = null
                    }
                } while (cookie != null)
                SearchOutcome(delivered, truncated)
            } catch (t: Throwable) {
                failure = t
                throw t
            } finally {
                release(connection, failure)
            }
        }

    internal fun release(connection: LDAPConnection, failure: Throwable?) {
        when {
            failure is LDAPException -> pool.releaseConnectionAfterException(connection, failure)
            failure != null && failure !is CancellationException -> pool.releaseDefunctConnection(connection)
            else -> pool.releaseConnection(connection)
        }
    }

    /** Tells the server to discard paged-results state (page size 0). Best effort, blocking. */
    internal fun abandonPagedSearch(connection: LDAPConnection, spec: SearchSpec, cookie: ASN1OctetString) {
        runCatching {
            val request = com.unboundid.ldap.sdk.SearchRequest(spec.baseDn, spec.scope, spec.filter, "1.1")
            request.addControl(SimplePagedResultsControl(0, cookie, false))
            connection.search(request)
        }
    }

    private fun deleteLogged(dn: String) {
        logged("delete", dn, { ChangeRecords.toLdif(LDIFDeleteChangeRecord(dn)) }) { pool.delete(dn) }
    }

    private inline fun <T> logged(type: String, dn: String, noinline ldif: () -> String, block: () -> T): T {
        val started = System.currentTimeMillis()
        val result = try {
            block()
        } catch (e: Throwable) {
            report(type, dn, ldif, if (e is CancellationException) "Cancelled" else LdapErrors.describe(e), started)
            throw e
        }
        report(type, dn, ldif, null, started)
        return result
    }

    private fun report(type: String, dn: String, ldif: () -> String, error: String?, started: Long) {
        val listener = operationListener ?: return
        runCatching {
            listener.operationCompleted(
                LdapOperation(Instant.now(), config.id, config.displayName, type, dn, ldif(), error, System.currentTimeMillis() - started),
            )
        }
    }

    private fun operationName(record: LDIFChangeRecord): String = when (record.changeType) {
        com.unboundid.ldap.sdk.ChangeType.ADD -> "add"
        com.unboundid.ldap.sdk.ChangeType.DELETE -> "delete"
        com.unboundid.ldap.sdk.ChangeType.MODIFY -> "modify"
        else -> "moddn"
    }

    private fun checkWritable() {
        if (config.readOnly) throw LDAPException(ResultCode.UNWILLING_TO_PERFORM, "Connection '${config.displayName}' is read-only")
    }

    override fun close() {
        closed = true
        cursors.toList().forEach { it.releaseIfIdle(force = true) }
        cursors.clear()
        pool.close()
    }

    companion object {
        /** Blocking: opens a connection pool and reads the root DSE. */
        fun open(config: LdapConnectionConfig, password: String?, sslContextFactory: (LdapConnectionConfig) -> SSLContext): LdapSession {
            val options = LDAPConnectionOptions().apply {
                connectTimeoutMillis = config.connectTimeoutSeconds * 1000
                responseTimeoutMillis = config.responseTimeoutSeconds * 1000L
                setFollowReferrals(config.followReferrals)
                setUseSynchronousMode(false)
                if (config.security != SecurityMode.NONE && config.verifyHostname && !config.trustAllCertificates) {
                    sslSocketVerifier = HostNameSSLSocketVerifier(true)
                }
            }
            val sslContext = if (config.security == SecurityMode.NONE) null else sslContextFactory(config)
            val socketFactory = if (config.security == SecurityMode.LDAPS) sslContext!!.socketFactory else null
            val serverSet = SingleServerSet(config.host, config.port, socketFactory, options)
            val bindRequest = if (config.authMethod == AuthMethod.SIMPLE && config.bindDn.isNotBlank()) {
                SimpleBindRequest(config.bindDn, password ?: "")
            } else null
            val postConnect = if (config.security == SecurityMode.START_TLS) StartTLSPostConnectProcessor(sslContext) else null
            val pool = LDAPConnectionPool(serverSet, bindRequest, 1, config.maxConnections.coerceAtLeast(1), postConnect)
            pool.connectionPoolName = "LDAP Client: ${config.displayName}"
            pool.setRetryFailedOperationsDueToInvalidConnections(true)
            val rootDse = try {
                pool.rootDSE
            } catch (_: LDAPException) {
                null
            }
            return LdapSession(config, pool, rootDse)
        }
    }
}
