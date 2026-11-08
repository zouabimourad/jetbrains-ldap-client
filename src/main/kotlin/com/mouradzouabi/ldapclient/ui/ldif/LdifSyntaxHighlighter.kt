package com.mouradzouabi.ldapclient.ui.ldif

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Colors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

class LdifSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = LdifLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = pack(KEYS[tokenType])

    companion object {
        val COMMENT = createTextAttributesKey("LDIF_COMMENT", Colors.LINE_COMMENT)
        val KEYWORD = createTextAttributesKey("LDIF_KEYWORD", Colors.KEYWORD)
        val ATTRIBUTE = createTextAttributesKey("LDIF_ATTRIBUTE", Colors.INSTANCE_FIELD)
        val SEPARATOR = createTextAttributesKey("LDIF_SEPARATOR", Colors.OPERATION_SIGN)
        val DN = createTextAttributesKey("LDIF_DN", Colors.STRING)
        val BASE64 = createTextAttributesKey("LDIF_BASE64", Colors.DOC_COMMENT)
        val URL = createTextAttributesKey("LDIF_URL", Colors.METADATA)
        val CHANGE_TYPE = createTextAttributesKey("LDIF_CHANGE_TYPE", Colors.CONSTANT)

        private val KEYS = mapOf(
            LdifTokens.COMMENT to COMMENT,
            LdifTokens.KEYWORD to KEYWORD,
            LdifTokens.ATTRIBUTE to ATTRIBUTE,
            LdifTokens.SEPARATOR to SEPARATOR,
            LdifTokens.DN_VALUE to DN,
            LdifTokens.BASE64 to BASE64,
            LdifTokens.URL to URL,
            LdifTokens.CHANGE_TYPE to CHANGE_TYPE,
        )
    }
}
