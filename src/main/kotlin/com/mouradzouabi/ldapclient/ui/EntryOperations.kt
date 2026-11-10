package com.mouradzouabi.ldapclient.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.ResultCode
import com.mouradzouabi.ldapclient.core.LdapDn
import com.mouradzouabi.ldapclient.core.LdapErrors
import com.mouradzouabi.ldapclient.model.LdapEvents
import com.mouradzouabi.ldapclient.service.LdapConnectionManager
import com.mouradzouabi.ldapclient.service.LdapNotifications
import com.mouradzouabi.ldapclient.service.LdapProjectService
import com.mouradzouabi.ldapclient.ui.dialogs.NewEntryDialog
import com.mouradzouabi.ldapclient.ui.editor.LdapEntryVirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.datatransfer.StringSelection
import kotlin.coroutines.cancellation.CancellationException

/** Entry level operations shared by the tree, search results and the entry editor. All I/O runs off the EDT. */
object EntryOperations {

    fun copyDn(dn: String) = CopyPasteManager.getInstance().setContents(StringSelection(dn))

    fun open(project: Project, configId: String, dn: String) = LdapEntryVirtualFile.open(project, configId, dn)

    fun newChild(project: Project, configId: String, parentDn: String) {
        val scope = LdapProjectService.getInstance(project).scope
        scope.launch {
            val session = try {
                LdapConnectionManager.getInstance().connect(configId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LdapNotifications.error(project, "Cannot connect", LdapErrors.describe(e))
                return@launch
            }
            val schema = session.schemaAsync()
            val entry = withContext(UiDispatcher) {
                NewEntryDialog(project, parentDn, schema).let { if (it.showAndGet()) it.entry else null }
            } ?: return@launch
            try {
                withBackgroundProgress(project, "Creating ${entry.dn}") { session.add(entry) }
                LdapEvents.fire { it.childrenChanged(configId, LdapDn.parent(entry.dn) ?: parentDn) }
                withContext(UiDispatcher) { open(project, configId, entry.dn) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LdapNotifications.error(project, "Cannot create entry", LdapErrors.describe(e))
            }
        }
    }

    fun delete(project: Project, configId: String, dn: String) {
        val answer = Messages.showYesNoDialog(project, "Delete entry\n$dn ?", "Delete LDAP Entry", Messages.getWarningIcon())
        if (answer != Messages.YES) return
        val scope = LdapProjectService.getInstance(project).scope
        scope.launch {
            try {
                val session = LdapConnectionManager.getInstance().connect(configId)
                try {
                    withBackgroundProgress(project, "Deleting $dn") { session.delete(dn, recursive = false) }
                } catch (e: LDAPException) {
                    if (e.resultCode != ResultCode.NOT_ALLOWED_ON_NONLEAF) throw e
                    val recursive = withContext(UiDispatcher) {
                        Messages.showYesNoDialog(
                            project,
                            "Entry $dn has children.\nDelete it together with ALL its descendants?",
                            "Delete Subtree", Messages.getWarningIcon(),
                        ) == Messages.YES
                    }
                    if (!recursive) return@launch
                    withBackgroundProgress(project, "Deleting subtree $dn", cancellable = true) {
                        reportProgressUpdates { progress ->
                            session.delete(dn, recursive = true) { done, total ->
                                progress.update("$done / $total entries", done.toDouble() / total)
                            }
                        }
                    }
                }
                LdapEvents.fire { it.entryDeleted(configId, dn) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LdapNotifications.error(project, "Cannot delete entry", LdapErrors.describe(e))
                LdapDn.parent(dn)?.let { parent -> LdapEvents.fire { it.childrenChanged(configId, parent) } }
            }
        }
    }

    fun rename(project: Project, configId: String, dn: String) {
        val newRdn = Messages.showInputDialog(
            project, "New RDN for\n$dn", "Rename LDAP Entry", null, LdapDn.rdn(dn),
            object : InputValidatorEx {
                override fun getErrorText(inputString: String) = if (LdapDn.isValidRdn(inputString)) null else "Invalid RDN"
            },
        ) ?: return
        if (newRdn == LdapDn.rdn(dn)) return
        val scope = LdapProjectService.getInstance(project).scope
        scope.launch {
            try {
                val session = LdapConnectionManager.getInstance().connect(configId)
                withBackgroundProgress(project, "Renaming $dn") { session.rename(dn, newRdn) }
                val parent = LdapDn.parent(dn) ?: ""
                LdapEvents.fire {
                    it.entryDeleted(configId, dn)
                    it.childrenChanged(configId, parent)
                }
                withContext(UiDispatcher) { open(project, configId, LdapDn.child(newRdn, parent)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LdapNotifications.error(project, "Cannot rename entry", LdapErrors.describe(e))
            }
        }
    }
}
