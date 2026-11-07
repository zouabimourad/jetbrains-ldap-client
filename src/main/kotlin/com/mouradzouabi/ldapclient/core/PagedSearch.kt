package com.mouradzouabi.ldapclient.core

import com.unboundid.asn1.ASN1OctetString
import com.unboundid.ldap.sdk.Control
import com.unboundid.ldap.sdk.LDAPConnection
import com.unboundid.ldap.sdk.LDAPSearchException
import com.unboundid.ldap.sdk.ResultCode
import com.unboundid.ldap.sdk.SearchResultEntry
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A resumable cursor over search results, used for progressive loading of tree children.
 *
 * Paged-results cookies are bound to the connection on most servers, so the cursor keeps a pooled
 * connection between pages. Idle cursors give their connection back ([releaseIfIdle]); the next
 * [nextPage] call then transparently restarts the search and skips the entries already delivered.
 */
class PagedSearch internal constructor(
    private val session: LdapSession,
    val spec: SearchSpec,
    val pageSize: Int,
) {
    private val mutex = Mutex()
    private var connection: LDAPConnection? = null
    private var cookie: ASN1OctetString? = null

    @Volatile
    var delivered: Int = 0
        private set

    @Volatile
    var exhausted: Boolean = false
        private set

    /** Server refused to return everything (size limit) and paging is unavailable. */
    @Volatile
    var truncated: Boolean = false
        private set

    @Volatile
    internal var lastUsed: Long = System.currentTimeMillis()

    /** Fetches the next page. Returns an empty list once [exhausted]. Safe to call from any thread. */
    suspend fun nextPage(): List<SearchResultEntry> = mutex.withLock {
        withContext(Dispatchers.IO) { fetch() }
    }

    private suspend fun fetch(): List<SearchResultEntry> {
        if (exhausted || session.closed) return emptyList()
        lastUsed = System.currentTimeMillis()
        var skip = 0
        val conn = connection ?: session.pool.connection.also {
            connection = it
            session.register(this)
            skip = delivered
            cookie = null
        }
        val result = ArrayList<SearchResultEntry>(pageSize)
        try {
            while (true) {
                val controls = mutableListOf<Control>()
                val pageSpec = if (session.supportsPaging) {
                    controls += SimplePagedResultsControl(pageSize, cookie, false)
                    spec
                } else {
                    spec.copy(sizeLimit = MAX_WITHOUT_PAGING)
                }
                val page = ArrayList<SearchResultEntry>()
                val searchResult = conn.searchSuspending(pageSpec, controls) { synchronized(page) { page += it } }
                when (searchResult.resultCode) {
                    ResultCode.SUCCESS -> {}
                    ResultCode.SIZE_LIMIT_EXCEEDED -> truncated = true
                    else -> throw LDAPSearchException(searchResult)
                }
                val paged = SimplePagedResultsControl.get(searchResult)
                if (paged != null && paged.moreResultsToReturn() && !truncated) {
                    cookie = paged.cookie
                } else {
                    cookie = null
                    exhausted = true
                }
                for (entry in page) {
                    if (skip > 0) skip-- else result += entry
                }
                if (exhausted || result.isNotEmpty()) break
            }
        } catch (t: Throwable) {
            releaseConnection(t)
            throw t
        }
        delivered += result.size
        lastUsed = System.currentTimeMillis()
        if (exhausted) releaseConnection(null)
        return result
    }

    private fun releaseConnection(failure: Throwable?) {
        val conn = connection ?: return
        connection = null
        session.unregister(this)
        session.release(conn, failure)
    }

    /**
     * Gives the held connection back to the pool if the cursor is not currently fetching.
     * With [force] the cursor is also marked exhausted (used when the node is discarded).
     * Blocking (may send a request to discard server side paging state).
     */
    fun releaseIfIdle(force: Boolean = false) {
        if (!mutex.tryLock()) return
        try {
            if (force) exhausted = true
            val conn = connection ?: return
            cookie?.let { session.abandonPagedSearch(conn, spec, it) }
            cookie = null
            releaseConnection(null)
        } finally {
            mutex.unlock()
        }
    }

    /** Discards this cursor. Suspends until a running fetch completes. */
    suspend fun close() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                exhausted = true
                val conn = connection ?: return@withContext
                cookie?.let { session.abandonPagedSearch(conn, spec, it) }
                cookie = null
                releaseConnection(null)
            }
        }
    }

    internal val holdsConnection: Boolean get() = connection != null

    companion object {
        const val MAX_WITHOUT_PAGING = 2000
    }
}
