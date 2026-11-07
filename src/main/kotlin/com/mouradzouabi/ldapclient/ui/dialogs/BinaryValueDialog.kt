package com.mouradzouabi.ldapclient.ui.dialogs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.mouradzouabi.ldapclient.core.AttributeKind
import com.mouradzouabi.ldapclient.core.AttributeKinds
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Image
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Viewer/editor for binary values: hex dump, image preview, certificate details, GUID/SID decoding,
 * load from / save to file. Decoding and file I/O run on pooled threads.
 */
class BinaryValueDialog(
    private val project: Project,
    private val attribute: String,
    initial: ByteArray,
    private val kind: AttributeKind,
    private val editable: Boolean,
) : DialogWrapper(project, true) {

    var value: ByteArray? = null
        private set
    private var bytes: ByteArray = initial

    private val info = JBLabel()
    private val preview = JPanel(BorderLayout())
    private val content = JPanel(BorderLayout())

    init {
        title = "$attribute Value" + if (editable) "" else " (read-only)"
        if (!editable) setOKButtonText("Close")
        init()
        render()
    }

    override fun createCenterPanel(): JComponent {
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        if (editable) buttons.add(JButton("Load from File…").apply { addActionListener { load() } })
        buttons.add(JButton("Save to File…").apply { addActionListener { saveToFile() } })
        val top = JPanel(BorderLayout()).apply {
            add(info, BorderLayout.CENTER)
            add(buttons, BorderLayout.EAST)
            border = JBUI.Borders.emptyBottom(6)
        }
        content.add(top, BorderLayout.NORTH)
        content.add(preview, BorderLayout.CENTER)
        content.preferredSize = JBUI.size(720, 440)
        return content
    }

    private fun render() {
        val data = bytes
        info.text = buildString {
            append("${data.size} bytes")
            AttributeKinds.imageType(data)?.let { append(" — ").append(it) }
            if (kind == AttributeKind.GUID && data.size == 16) append(" — GUID ").append(AttributeKinds.formatGuid(data))
            if (kind == AttributeKind.SID) AttributeKinds.formatSid(data)?.let { append(" — ").append(it) }
        }
        preview.removeAll()
        preview.add(JBLabel("Decoding…", SwingConstants.CENTER), BorderLayout.CENTER)
        preview.revalidate()
        val modality = ModalityState.stateForComponent(content)
        ApplicationManager.getApplication().executeOnPooledThread {
            val component = buildPreview(data)
            ApplicationManager.getApplication().invokeLater({
                if (data !== bytes) return@invokeLater
                preview.removeAll()
                preview.add(component(), BorderLayout.CENTER)
                preview.revalidate()
                preview.repaint()
            }, modality)
        }
    }

    /** Runs on a pooled thread; returns a factory invoked on the EDT. */
    private fun buildPreview(data: ByteArray): () -> JComponent {
        if (data.isEmpty()) return { JBLabel(if (editable) "Empty — load a file" else "Empty", SwingConstants.CENTER) }
        AttributeKinds.imageType(data)?.let {
            val image = runCatching { ImageIO.read(ByteArrayInputStream(data)) }.getOrNull()
            if (image != null) {
                val max = 400.0
                val scale = minOf(1.0, max / image.width, max / image.height)
                val scaled = image.getScaledInstance((image.width * scale).toInt().coerceAtLeast(1), (image.height * scale).toInt().coerceAtLeast(1), Image.SCALE_SMOOTH)
                return { JBLabel(ImageIcon(scaled)).apply { text = "${image.width} × ${image.height}"; horizontalTextPosition = SwingConstants.CENTER; verticalTextPosition = SwingConstants.BOTTOM } }
            }
        }
        val text = AttributeKinds.parseCertificate(data)?.toString() ?: hexDump(data)
        return {
            JBScrollPane(JBTextArea(text).apply {
                isEditable = false
                font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size)
                caretPosition = 0
            })
        }
    }

    private fun hexDump(data: ByteArray, limit: Int = 64 * 1024): String {
        val sb = StringBuilder()
        val shown = minOf(limit, data.size)
        for (offset in 0 until shown step 16) {
            sb.append("%08x  ".format(offset))
            val end = minOf(offset + 16, shown)
            for (i in offset until offset + 16) {
                if (i < end) sb.append("%02x ".format(data[i])) else sb.append("   ")
                if (i == offset + 7) sb.append(' ')
            }
            sb.append(" |")
            for (i in offset until end) {
                val c = data[i].toInt() and 0xff
                sb.append(if (c in 0x20..0x7e) c.toChar() else '.')
            }
            sb.append("|\n")
        }
        if (data.size > shown) sb.append("… ${data.size - shown} more bytes\n")
        return sb.toString()
    }

    private fun load() {
        val file = FileChooser.chooseFile(FileChooserDescriptorFactory.singleFile(), project, null) ?: return
        val modality = ModalityState.stateForComponent(content)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { file.contentsToByteArray() }
            ApplicationManager.getApplication().invokeLater({
                result.onSuccess {
                    bytes = it
                    value = it
                    render()
                }.onFailure { info.text = "Cannot read file: ${it.message}" }
            }, modality)
        }
    }

    private fun saveToFile() {
        val extension = when (AttributeKinds.imageType(bytes)) {
            "JPEG image" -> "jpg"
            "PNG image" -> "png"
            "GIF image" -> "gif"
            else -> if (kind == AttributeKind.CERTIFICATE) "der" else "bin"
        }
        val descriptor = FileSaverDescriptor("Save $attribute", "Save the binary value to a file", extension)
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save("$attribute.$extension")?.file ?: return
        val data = bytes
        val modality = ModalityState.stateForComponent(content)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { target.writeBytes(data) }
            ApplicationManager.getApplication().invokeLater({
                info.text = result.fold({ "Saved ${data.size} bytes to ${target.path}" }, { "Cannot write file: ${it.message}" })
            }, modality)
        }
    }

    override fun doOKAction() {
        if (editable && value == null && bytes.isNotEmpty()) value = bytes
        super.doOKAction()
    }
}
