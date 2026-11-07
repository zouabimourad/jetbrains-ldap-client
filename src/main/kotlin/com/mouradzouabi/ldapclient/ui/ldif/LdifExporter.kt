package com.mouradzouabi.ldapclient.ui.ldif

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportRawProgress
import com.mouradzouabi.ldapclient.core.LdapErrors
import com.mouradzouabi.ldapclient.core.LdifExport
import com.mouradzouabi.ldapclient.model.LdapSettings
import com.mouradzouabi.ldapclient.service.LdapConnectionManager
import com.mouradzouabi.ldapclient.service.LdapNotifications
import com.mouradzouabi.ldapclient.service.LdapProjectService
import com.mouradzouabi.ldapclient.ui.UiDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.time.OffsetDateTime

/** Exports an entry / subtree to an LDIF file in the background. */
object LdifExporter {

    fun export(project: Project, configId: String, dn: String) {
        val dialog = LdifExportDialog(project, dn)
        if (!dialog.showAndGet()) return
        val file = dialog.file
        val options = dialog.options
        val open = dialog.openAfterExport
        val connectionName = LdapSettings.getInstance().find(configId)?.displayName ?: configId
        val header = "Exported from $connectionName, base: $dn, scope: ${options.scope.name.lowercase()}, " +
            "filter: ${options.filter}, at ${OffsetDateTime.now().withNano(0)}"

        LdapProjectService.getInstance(project).scope.launch {
            val started = System.currentTimeMillis()
            try {
                val session = LdapConnectionManager.getInstance().connect(configId)
                val summary = withBackgroundProgress(project, "Exporting LDIF from $dn", cancellable = true) {
                    reportRawProgress { reporter ->
                        withContext(Dispatchers.IO) {
                            Files.newOutputStream(file).buffered().use { out ->
                                LdifExport.export(session, dn, options.copy(header = header), out) { count ->
                                    reporter.details("$count entries written")
                                }
                            }
                        }
                    }
                }
                val elapsed = System.currentTimeMillis() - started
                LdapNotifications.info(project, "LDIF export", "Exported ${summary.entries} entries to $file in $elapsed ms")
                if (open) {
                    val vf = withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) }
                    if (vf != null) withContext(UiDispatcher) { OpenFileDescriptor(project, vf).navigate(true) }
                }
            } catch (e: CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { Files.deleteIfExists(file) }
                throw e
            } catch (e: Exception) {
                runCatching { Files.deleteIfExists(file) }
                LdapNotifications.error(project, "LDIF export failed", LdapErrors.describe(e))
            }
        }
    }
}
