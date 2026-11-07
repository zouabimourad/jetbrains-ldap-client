package com.mouradzouabi.ldapclient.core

import com.unboundid.ldap.sdk.AsyncRequestID
import com.unboundid.ldap.sdk.AsyncSearchResultListener
import com.unboundid.ldap.sdk.Control
import com.unboundid.ldap.sdk.Filter
import com.unboundid.ldap.sdk.LDAPConnection
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.SearchRequest
import com.unboundid.ldap.sdk.SearchResult
import com.unboundid.ldap.sdk.SearchResultEntry
import com.unboundid.ldap.sdk.SearchResultReference
import com.unboundid.ldap.sdk.SearchScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SearchSpec(
    val baseDn: String,
    val scope: SearchScope,
    val filter: Filter,
    val attributes: List<String>,
    val sizeLimit: Int = 0,
    val timeLimitSeconds: Int = 0,
)

/**
 * Runs an asynchronous search on [this] connection. Entries are streamed to [onEntry] (called from the
 * LDAP SDK reader thread, keep it cheap). If the calling coroutine is cancelled the operation is
 * abandoned on the server, so no thread stays blocked.
 */
suspend fun LDAPConnection.searchSuspending(
    spec: SearchSpec,
    controls: List<Control> = emptyList(),
    onEntry: (SearchResultEntry) -> Unit,
): SearchResult = suspendCancellableCoroutine { cont ->
    val listener = object : AsyncSearchResultListener {
        override fun searchEntryReturned(entry: SearchResultEntry) = onEntry(entry)
        override fun searchReferenceReturned(reference: SearchResultReference) {}
        override fun searchResultReceived(requestID: AsyncRequestID, result: SearchResult) {
            if (cont.isActive) cont.resume(result)
        }
    }
    val request = SearchRequest(listener, spec.baseDn, spec.scope, spec.filter, *spec.attributes.toTypedArray())
    request.sizeLimit = spec.sizeLimit
    request.timeLimitSeconds = spec.timeLimitSeconds
    if (controls.isNotEmpty()) request.setControls(controls)
    val id = try {
        asyncSearch(request)
    } catch (e: LDAPException) {
        cont.resumeWithException(e)
        return@suspendCancellableCoroutine
    }
    cont.invokeOnCancellation { runCatching { abandon(id) } }
}
