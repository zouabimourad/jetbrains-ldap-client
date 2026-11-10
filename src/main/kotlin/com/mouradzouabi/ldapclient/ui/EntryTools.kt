package com.mouradzouabi.ldapclient.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.mouradzouabi.ldapclient.core.EntryTemplate
import com.mouradzouabi.ldapclient.core.EntryText
import com.mouradzouabi.ldapclient.core.LdapDn
import com.mouradzouabi.ldapclient.core.LdapErrors
import com.mouradzouabi.ldapclient.core.SubtreeCopy
import com.mouradzouabi.ldapclient.model.LdapEvents
import com.mouradzouabi.ldapclient.model.LdapSettings
import com.mouradzouabi.ldapclient.service.LdapConnectionManager
import com.mouradzouabi.ldapclient.service.LdapNotifications
import com.mouradzouabi.ldapclient.service.LdapProjectService
import com.mouradzouabi.ldapclient.ui.dialogs.AccountStatusDialog
import com.mouradzouabi.ldapclient.ui.dialogs.CompareWithDialog
import com.mouradzouabi.ldapclient.ui.dialogs.CopyMoveDialog
import com.mouradzouabi.ldapclient.ui.dialogs.NewEntryDialog
import com.mouradzouabi.ldapclient.ui.ldif.LdifFileType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** An entry on a connection. */
data class EntryRef(val configId: String, val dn: String)

/** Duplicate, compare, copy / move and account tools shared by the tree, search results and the entry editor. */
object EntryTools {

    fun duplicate(project: Project, configId: String, dn: String) = launch(project, "Cannot duplicate entry") {
        val session = LdapConnectionManager.getInstance().connect(configId)
        val schema = session.schemaAsync()
        val entry = session.getEntry(dn, listOf("*")) ?: error("Entry $dn not found")
        val parentDn = LdapDn.parent(dn).orEmpty()
        val copy = withContext(UiDispatcher) {
            NewEntryDialog(project, parentDn, schema, EntryTemplate.duplicate(entry, schema), "Duplicate ${LdapDn.rdn(dn)}")
                .let { if (it.showAndGet()) it.entry else null }
        } ?: return@launch
        withBackgroundProgress(project, "Creating ${copy.dn}") { session.add(copy) }
        LdapEvents.fire { it.childrenChanged(configId, LdapDn.parent(copy.dn) ?: parentDn) }
        withContext(UiDispatcher) { EntryOperations.open(project, configId, copy.dn) }
    }

    fun compareWith(project: Project, configId: String, dn: String) {
        val dialog = CompareWithDialog(project, configId, dn)
        if (!dialog.showAndGet()) return
        compare(project, EntryRef(configId, dn), EntryRef(dialog.targetConfig.id, dialog.targetDn))
    }

    /** Shows both entries side by side in the diff viewer, as sorted LDIF with password values hashed. */
    fun compare(project: Project, left: EntryRef, right: EntryRef) = launch(project, "Cannot compare entries") {
        val operational = LdapSettings.getInstance().showOperationalAttributes
        val attributes = if (operational) listOf("*", "+") else listOf("*")
        val (leftText, rightText) = withBackgroundProgress(project, "Comparing entries") {
            coroutineScope {
                val a = async { text(left, attributes, operational) }
                val b = async { text(right, attributes, operational) }
                a.await() to b.await()
            }
        }
        withContext(UiDispatcher) {
            val factory = DiffContentFactory.getInstance()
            val request = SimpleDiffRequest(
                "Compare ${LdapDn.rdn(left.dn)} and ${LdapDn.rdn(right.dn)}",
                factory.create(project, leftText, LdifFileType),
                factory.create(project, rightText, LdifFileType),
                label(left), label(right),
            )
            DiffManager.getInstance().showDiff(project, request)
        }
    }

    private suspend fun text(ref: EntryRef, attributes: List<String>, operational: Boolean): String {
        val session = LdapConnectionManager.getInstance().connect(ref.configId)
        val entry = session.getEntry(ref.dn, attributes) ?: error("Entry ${ref.dn} not found on ${session.config.displayName}")
        return EntryText.canonical(entry, session.schemaAsync(), operational)
    }

    private fun label(ref: EntryRef): String {
        val name = LdapSettings.getInstance().find(ref.configId)?.displayName ?: ref.configId
        return "$name: ${ref.dn}"
    }

    fun copyOrMove(project: Project, configId: String, dn: String, move: Boolean) {
        val dialog = CopyMoveDialog(project, configId, dn, move)
        if (!dialog.showAndGet()) return
        val targetId = dialog.targetConfig.id
        val targetDn = dialog.targetDn
        val isMove = dialog.isMove
        val verb = if (isMove) "move" else "copy"
        launch(project, "Cannot $verb entry") {
            val manager = LdapConnectionManager.getInstance()
            val source = manager.connect(configId)
            val target = manager.connect(targetId)
            val sourceParent = LdapDn.parent(dn).orEmpty()
            val targetParent = LdapDn.parent(targetDn).orEmpty()
            if (isMove && targetId == configId && dialog.recursive) {
                withBackgroundProgress(project, "Moving $dn") {
                    val newParent = targetParent.takeUnless { LdapDn.same(it, sourceParent) }
                    source.move(dn, LdapDn.rdn(targetDn), newParent)
                }
                LdapEvents.fire {
                    it.entryDeleted(configId, dn)
                    it.childrenChanged(configId, sourceParent)
                    it.childrenChanged(configId, targetParent)
                }
                LdapNotifications.info(project, "Entry moved", "$dn → $targetDn")
                return@launch
            }
            val copy = SubtreeCopy(source, target, dn, targetDn, dialog.recursive, dialog.conflictPolicy, dialog.stopAtFirstError)
            val result = withBackgroundProgress(project, "${verb.replaceFirstChar { it.uppercase() }}ing $dn", cancellable = true) {
                reportProgressUpdates { progress -> copy.run { count, current -> progress.update("$count entries — $current") } }
            }
            LdapEvents.fire { it.childrenChanged(targetId, targetParent) }
            var deleted = false
            if (isMove && result.succeeded) {
                withBackgroundProgress(project, "Deleting $dn", cancellable = true) { source.delete(dn, recursive = dialog.recursive) }
                LdapEvents.fire { it.entryDeleted(configId, dn) }
                deleted = true
            }
            val summary = buildString {
                append("${result.copied} created")
                if (result.updated > 0) append(", ${result.updated} updated")
                if (result.skipped > 0) append(", ${result.skipped} skipped")
                if (result.failures.isNotEmpty()) append(", ${result.failures.size} failed")
                if (result.stopped) append(" (stopped)")
                if (deleted) append("; source deleted")
                else if (isMove) append("; source kept because of errors")
            }
            if (result.failures.isEmpty()) LdapNotifications.info(project, "${verb.replaceFirstChar { it.uppercase() }} to $targetDn", summary)
            else LdapNotifications.error(
                project, "${verb.replaceFirstChar { it.uppercase() }} to $targetDn",
                summary + "\n" + result.failures.take(5).joinToString("\n") { "${it.dn}: ${it.message}" },
            )
        }
    }

    fun accountStatus(project: Project, configId: String, dn: String) = AccountStatusDialog(project, configId, dn).show()

    private fun launch(project: Project, errorTitle: String, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        LdapProjectService.getInstance(project).scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LdapNotifications.error(project, errorTitle, LdapErrors.describe(e))
            }
        }
    }
}
