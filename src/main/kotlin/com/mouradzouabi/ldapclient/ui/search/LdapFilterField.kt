package com.mouradzouabi.ldapclient.ui.search

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.project.Project
import com.intellij.ui.TextFieldWithAutoCompletionListProvider
import com.intellij.util.textCompletion.TextFieldWithCompletion
import com.mouradzouabi.ldapclient.core.LdapSchema

/**
 * Single-line LDAP filter editor with syntax highlighting and schema based completion:
 * attribute names after `(` and object class names after `objectClass=`.
 */
class LdapFilterField(project: Project, text: String, private val provider: FilterCompletionProvider = FilterCompletionProvider()) :
    TextFieldWithCompletion(project, provider, text, true, true, false) {

    var schema: LdapSchema
        get() = provider.schema
        set(value) {
            provider.schema = value
        }

    override fun createEditor(): EditorEx = super.createEditor().also {
        it.highlighter = LexerEditorHighlighter(LdapFilterHighlighter(), EditorColorsManager.getInstance().globalScheme)
    }
}

class FilterCompletionProvider : TextFieldWithAutoCompletionListProvider<String>(emptyList()) {
    @Volatile
    var schema: LdapSchema = LdapSchema(null)

    override fun getLookupString(item: String) = item

    override fun getPrefix(text: String, offset: Int): String = text.substring(prefixStart(text, offset), offset)

    override fun getItems(prefix: String?, cached: Boolean, parameters: CompletionParameters?): Collection<String> {
        val text = parameters?.originalFile?.text ?: return emptyList()
        val offset = parameters.offset.coerceIn(0, text.length)
        return when (FilterContext.at(text, offset)) {
            FilterContext.ATTRIBUTE -> (schema.attributeNames + COMMON_ATTRIBUTES).distinctBy { it.lowercase() }
            FilterContext.OBJECT_CLASS -> schema.objectClassNames
            FilterContext.NONE -> emptyList()
        }
    }

    companion object {
        private val COMMON_ATTRIBUTES = listOf("objectClass", "cn", "uid", "sn", "mail", "member", "memberOf", "ou", "dc", "description")

        fun prefixStart(text: String, offset: Int): Int {
            var start = offset
            while (start > 0 && LdapFilterLexer.isAttributeChar(text[start - 1])) start--
            return start
        }
    }
}

enum class FilterContext {
    ATTRIBUTE, OBJECT_CLASS, NONE;

    companion object {
        /** What is being typed at [offset]: an attribute name, an objectClass value or something else. */
        fun at(text: String, offset: Int): FilterContext {
            val start = FilterCompletionProvider.prefixStart(text, offset)
            val before = text.substring(0, start).trimEnd()
            if (before.isEmpty() || before.endsWith("(")) return ATTRIBUTE
            if (before.endsWith("=")) {
                val attribute = before.dropLast(1).trimEnd('~', '<', '>').takeLastWhile(LdapFilterLexer::isAttributeChar)
                if (attribute.equals("objectClass", ignoreCase = true)) return OBJECT_CLASS
            }
            return NONE
        }
    }
}
