package com.mouradzouabi.ldapclient.ui.search

import com.intellij.lexer.LexerBase
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Colors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

object FilterTokens {
    val PAREN = IElementType("LDAP_FILTER_PAREN", null)
    val OPERATOR = IElementType("LDAP_FILTER_OPERATOR", null)
    val ATTRIBUTE = IElementType("LDAP_FILTER_ATTRIBUTE", null)
    val COMPARISON = IElementType("LDAP_FILTER_COMPARISON", null)
    val VALUE = IElementType("LDAP_FILTER_VALUE", null)
    val WILDCARD = IElementType("LDAP_FILTER_WILDCARD", null)
    val ESCAPE = IElementType("LDAP_FILTER_ESCAPE", null)
    val WHITESPACE = IElementType("LDAP_FILTER_WHITESPACE", null)
    val BAD = IElementType("LDAP_FILTER_BAD", null)
}

/** Lexer for RFC 4515 search filters, e.g. `(&(objectClass=person)(cn=jo*))`. */
class LdapFilterLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null
    private var state = START
    private var tokenState = START

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        bufferEnd = endOffset
        tokenEnd = startOffset
        state = initialState
        locate()
    }

    override fun getState() = tokenState
    override fun getTokenType() = tokenType
    override fun getTokenStart() = tokenStart
    override fun getTokenEnd() = tokenEnd
    override fun advance() = locate()
    override fun getBufferSequence() = buffer
    override fun getBufferEnd() = bufferEnd

    private fun locate() {
        tokenStart = tokenEnd
        tokenState = state
        if (tokenStart >= bufferEnd) {
            tokenType = null
            return
        }
        val c = buffer[tokenStart]
        when (state) {
            VALUE -> when {
                c == ')' -> token(FilterTokens.PAREN, tokenStart + 1, START)
                c == '*' -> token(FilterTokens.WILDCARD, tokenStart + 1, VALUE)
                c == '\\' -> {
                    var end = tokenStart + 1
                    while (end < bufferEnd && end < tokenStart + 3 && buffer[end].isHex()) end++
                    token(if (end == tokenStart + 3) FilterTokens.ESCAPE else FilterTokens.BAD, maxOf(end, tokenStart + 1), VALUE)
                }
                else -> token(FilterTokens.VALUE, scan(tokenStart) { it != ')' && it != '*' && it != '\\' }, VALUE)
            }
            COMPARATOR -> when {
                c == '=' -> token(FilterTokens.COMPARISON, tokenStart + 1, VALUE)
                (c == '~' || c == '<' || c == '>') && at(tokenStart + 1) == '=' -> token(FilterTokens.COMPARISON, tokenStart + 2, VALUE)
                c == ':' -> {
                    val end = scan(tokenStart) { it != '=' && it != ')' }
                    if (at(end) == '=') token(FilterTokens.COMPARISON, end + 1, VALUE) else token(FilterTokens.BAD, end, VALUE)
                }
                else -> token(FilterTokens.BAD, tokenStart + 1, VALUE)
            }
            else -> when {
                c.isWhitespace() -> token(FilterTokens.WHITESPACE, scan(tokenStart) { it.isWhitespace() }, state)
                c == '(' -> token(FilterTokens.PAREN, tokenStart + 1, AFTER_OPEN)
                c == ')' -> token(FilterTokens.PAREN, tokenStart + 1, START)
                state == AFTER_OPEN && (c == '&' || c == '|' || c == '!') -> token(FilterTokens.OPERATOR, tokenStart + 1, START)
                isAttributeChar(c) -> token(FilterTokens.ATTRIBUTE, scan(tokenStart, ::isAttributeChar), COMPARATOR)
                else -> token(FilterTokens.BAD, tokenStart + 1, state)
            }
        }
    }

    private fun token(type: IElementType, end: Int, next: Int) {
        tokenType = type
        tokenEnd = end
        state = next
    }

    private fun at(index: Int): Char? = if (index < bufferEnd) buffer[index] else null

    private inline fun scan(from: Int, accept: (Char) -> Boolean): Int {
        var i = from
        while (i < bufferEnd && accept(buffer[i])) i++
        return if (i == from) from + 1 else i
    }

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    companion object {
        const val START = 0
        const val AFTER_OPEN = 1
        const val COMPARATOR = 2
        const val VALUE = 3

        fun isAttributeChar(c: Char) = c.isLetterOrDigit() || c == '-' || c == '.' || c == ';' || c == '_'
    }
}

class LdapFilterHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer() = LdapFilterLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = pack(KEYS[tokenType])

    companion object {
        private val KEYS = mapOf(
            FilterTokens.PAREN to createTextAttributesKey("LDAP_FILTER_PAREN", Colors.PARENTHESES),
            FilterTokens.OPERATOR to createTextAttributesKey("LDAP_FILTER_OPERATOR", Colors.KEYWORD),
            FilterTokens.ATTRIBUTE to createTextAttributesKey("LDAP_FILTER_ATTRIBUTE", Colors.INSTANCE_FIELD),
            FilterTokens.COMPARISON to createTextAttributesKey("LDAP_FILTER_COMPARISON", Colors.OPERATION_SIGN),
            FilterTokens.VALUE to createTextAttributesKey("LDAP_FILTER_VALUE", Colors.STRING),
            FilterTokens.WILDCARD to createTextAttributesKey("LDAP_FILTER_WILDCARD", Colors.KEYWORD),
            FilterTokens.ESCAPE to createTextAttributesKey("LDAP_FILTER_ESCAPE", Colors.VALID_STRING_ESCAPE),
            FilterTokens.BAD to createTextAttributesKey("LDAP_FILTER_BAD", HighlighterColors.BAD_CHARACTER),
        )
    }
}
