package com.mouradzouabi.ldapclient.ui.ldif

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

object LdifTokens {
    val COMMENT = IElementType("LDIF_COMMENT", null)
    val KEYWORD = IElementType("LDIF_KEYWORD", null)
    val ATTRIBUTE = IElementType("LDIF_ATTRIBUTE", null)
    val SEPARATOR = IElementType("LDIF_SEPARATOR", null)
    val VALUE = IElementType("LDIF_VALUE", null)
    val DN_VALUE = IElementType("LDIF_DN_VALUE", null)
    val BASE64 = IElementType("LDIF_BASE64", null)
    val URL = IElementType("LDIF_URL", null)
    val CHANGE_TYPE = IElementType("LDIF_CHANGE_TYPE", null)
    val WHITESPACE = IElementType("LDIF_WHITESPACE", null)

    val KEYWORDS = setOf(
        "dn", "changetype", "control", "version", "newrdn", "deleteoldrdn", "newsuperior",
        "add", "replace", "delete", "increment",
    )
}

/**
 * Line oriented LDIF lexer. The state fully describes the position (line start with the kind of a
 * possible folded continuation, or the part of an `attr: value` line), so lexing can restart anywhere.
 */
class LdifLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null
    private var state = LINE_PLAIN
    private var tokenState = LINE_PLAIN

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.bufferEnd = endOffset
        this.tokenEnd = startOffset
        this.state = initialState
        locateToken()
    }

    override fun getState() = tokenState
    override fun getTokenType() = tokenType
    override fun getTokenStart() = tokenStart
    override fun getTokenEnd() = tokenEnd
    override fun advance() = locateToken()
    override fun getBufferSequence() = buffer
    override fun getBufferEnd() = bufferEnd

    private fun lineEnd(from: Int): Int {
        var i = from
        while (i < bufferEnd && buffer[i] != '\n') i++
        return i
    }

    private fun inclNewline(lineEnd: Int) = if (lineEnd < bufferEnd) lineEnd + 1 else lineEnd

    private fun locateToken() {
        tokenStart = tokenEnd
        tokenState = state
        if (tokenStart >= bufferEnd) {
            tokenType = null
            return
        }
        val pos = tokenStart
        val eol = lineEnd(pos)
        when (state) {
            LINE_PLAIN, LINE_AFTER_COMMENT, LINE_AFTER_VALUE, LINE_AFTER_BASE64 -> lineStart(pos, eol)
            MID_SEPARATOR, MID_SEPARATOR_CHANGETYPE, MID_SEPARATOR_DN -> separator(pos, eol)
            else -> {
                tokenType = when (state) {
                    MID_BASE64 -> LdifTokens.BASE64
                    MID_URL -> LdifTokens.URL
                    MID_CHANGETYPE -> LdifTokens.CHANGE_TYPE
                    MID_DN -> LdifTokens.DN_VALUE
                    else -> LdifTokens.VALUE
                }
                tokenEnd = inclNewline(eol)
                state = if (state == MID_BASE64) LINE_AFTER_BASE64 else LINE_AFTER_VALUE
            }
        }
    }

    private fun lineStart(pos: Int, eol: Int) {
        val c = buffer[pos]
        when {
            c == '\n' -> {
                tokenType = LdifTokens.WHITESPACE
                tokenEnd = pos + 1
                state = LINE_PLAIN
            }
            c == ' ' -> {
                tokenType = when (state) {
                    LINE_AFTER_COMMENT -> LdifTokens.COMMENT
                    LINE_AFTER_BASE64 -> LdifTokens.BASE64
                    else -> LdifTokens.VALUE
                }
                tokenEnd = inclNewline(eol)
                if (state == LINE_PLAIN) state = LINE_AFTER_VALUE
            }
            c == '#' -> {
                tokenType = LdifTokens.COMMENT
                tokenEnd = inclNewline(eol)
                state = LINE_AFTER_COMMENT
            }
            buffer.subSequence(pos, eol).trimEnd() == "-" -> {
                tokenType = LdifTokens.SEPARATOR
                tokenEnd = inclNewline(eol)
                state = LINE_PLAIN
            }
            else -> {
                var colon = pos
                while (colon < eol && buffer[colon] != ':') colon++
                val name = buffer.subSequence(pos, colon).toString().lowercase()
                val base = name.substringBefore(';')
                tokenType = if (base in LdifTokens.KEYWORDS) LdifTokens.KEYWORD else LdifTokens.ATTRIBUTE
                if (colon >= eol) {
                    tokenEnd = inclNewline(eol)
                    state = LINE_PLAIN
                } else {
                    tokenEnd = colon
                    state = when (base) {
                        "changetype" -> MID_SEPARATOR_CHANGETYPE
                        "dn", "newsuperior" -> MID_SEPARATOR_DN
                        else -> MID_SEPARATOR
                    }
                }
            }
        }
    }

    private fun separator(pos: Int, eol: Int) {
        val next = if (pos + 1 < eol) buffer[pos + 1] else '\u0000'
        val length = if (next == ':' || next == '<') 2 else 1
        var p = pos + length
        while (p < eol && buffer[p] == ' ') p++
        tokenType = LdifTokens.SEPARATOR
        if (p >= eol) {
            tokenEnd = inclNewline(eol)
            state = if (next == ':') LINE_AFTER_BASE64 else LINE_AFTER_VALUE
            return
        }
        tokenEnd = p
        state = when {
            next == ':' -> MID_BASE64
            next == '<' -> MID_URL
            state == MID_SEPARATOR_CHANGETYPE -> MID_CHANGETYPE
            state == MID_SEPARATOR_DN -> MID_DN
            else -> MID_VALUE
        }
    }

    private companion object {
        const val LINE_PLAIN = 0
        const val LINE_AFTER_COMMENT = 1
        const val LINE_AFTER_VALUE = 2
        const val LINE_AFTER_BASE64 = 3
        const val MID_SEPARATOR = 4
        const val MID_SEPARATOR_CHANGETYPE = 5
        const val MID_SEPARATOR_DN = 6
        const val MID_VALUE = 7
        const val MID_BASE64 = 8
        const val MID_URL = 9
        const val MID_CHANGETYPE = 10
        const val MID_DN = 11
    }
}
