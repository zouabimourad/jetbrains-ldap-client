package com.mouradzouabi.ldapclient.service

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job

/** Provides coroutine scopes bound to the project lifetime and to UI component lifetimes. */
@Service(Service.Level.PROJECT)
class LdapProjectService(val project: Project, private val cs: CoroutineScope) {

    /** Scope for operations that should outlive a UI component (cancelled when the project closes). */
    val scope: CoroutineScope get() = cs

    /** A child scope cancelled when [disposable] is disposed. */
    fun childScope(disposable: Disposable): CoroutineScope {
        val job = SupervisorJob(cs.coroutineContext.job)
        Disposer.register(disposable) { job.cancel() }
        return CoroutineScope(cs.coroutineContext + job)
    }

    companion object {
        fun getInstance(project: Project): LdapProjectService = project.service()
    }
}

object LdapNotifications {
    private const val GROUP = "LDAP Client"

    fun error(project: Project?, title: String, message: String) = notify(project, title, message, NotificationType.ERROR)

    fun info(project: Project?, title: String, message: String) = notify(project, title, message, NotificationType.INFORMATION)

    private fun notify(project: Project?, title: String, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification(title, StringUtil.escapeXmlEntities(message), type)
            .notify(project)
    }
}
