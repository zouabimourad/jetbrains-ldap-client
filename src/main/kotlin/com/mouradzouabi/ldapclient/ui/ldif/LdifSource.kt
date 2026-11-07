package com.mouradzouabi.ldapclient.ui.ldif

import com.intellij.openapi.vfs.VirtualFile
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader

/**
 * Where an LDIF script comes from: a file (streamed, never fully loaded) or a snapshot of editor text.
 * [lineOffset] maps record lines back to the document when only a selection is run.
 */
class LdifSource private constructor(
    val title: String,
    val file: VirtualFile?,
    val lineOffset: Int,
    private val text: String?,
) {
    /** Identifies the source for reusing result tabs. */
    val key: String = (file?.url ?: title) + if (text != null) "#text@$lineOffset" else ""

    val isSelection: Boolean get() = text != null && lineOffset > 0

    /** Blocking: call on a background thread. Returns the reader and its approximate size in chars. */
    fun open(): Pair<Reader, Long> = if (text != null) {
        StringReader(text) to text.length.toLong()
    } else {
        val f = file!!
        InputStreamReader(f.inputStream, Charsets.UTF_8) to f.length
    }

    companion object {
        fun ofFile(file: VirtualFile) = LdifSource(file.name, file, 0, null)

        fun ofText(title: String, text: String, file: VirtualFile?, lineOffset: Int) = LdifSource(title, file, lineOffset, text)
    }
}
