package com.mouradzouabi.ldapclient.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.mouradzouabi.ldapclient.ui.ldif.LdifFileType
import javax.swing.JComponent

/** Read-only, LDIF-highlighted editor component. Released with [parent]. */
class LdifViewer(project: Project?, parent: Disposable, text: String = "") {
    private val document = EditorFactory.getInstance().createDocument(StringUtil.convertLineSeparators(text))
    private val editor = EditorFactory.getInstance().createViewer(document, project) as EditorEx

    init {
        editor.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, LdifFileType)
        editor.settings.apply {
            isLineNumbersShown = false
            isFoldingOutlineShown = false
            isLineMarkerAreaShown = false
            additionalLinesCount = 0
            additionalColumnsCount = 0
            isCaretRowShown = false
            isUseSoftWraps = true
        }
        editor.setBorder(null)
        Disposer.register(parent) { EditorFactory.getInstance().releaseEditor(editor) }
    }

    val component: JComponent get() = editor.component

    var text: String
        get() = document.text
        set(value) {
            ApplicationManager.getApplication().runWriteAction { document.setText(StringUtil.convertLineSeparators(value)) }
            editor.scrollingModel.scrollVertically(0)
        }
}
