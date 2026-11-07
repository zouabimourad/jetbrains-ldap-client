package com.mouradzouabi.ldapclient.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.mouradzouabi.ldapclient.core.LdapOperation
import com.mouradzouabi.ldapclient.core.LdapOperationListener
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory history of the write operations sent to all servers (newest last). It is never persisted,
 * and password values are already masked by the session.
 */
@Service(Service.Level.APP)
class LdapOperationLog : LdapOperationListener {
    private val operations = ArrayDeque<LdapOperation>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    override fun operationCompleted(operation: LdapOperation) {
        synchronized(operations) {
            operations.addLast(operation)
            while (operations.size > MAX_OPERATIONS) operations.removeFirst()
        }
        listeners.forEach { it() }
    }

    fun snapshot(): List<LdapOperation> = synchronized(operations) { operations.toList() }

    fun clear() {
        synchronized(operations) { operations.clear() }
        listeners.forEach { it() }
    }

    /** [listener] is called on the thread that performed the operation. */
    fun addListener(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    companion object {
        const val MAX_OPERATIONS = 1000

        fun getInstance(): LdapOperationLog = service()
    }
}
