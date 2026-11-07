package com.mouradzouabi.ldapclient.ui

import com.intellij.psi.tree.IElementType
import com.mouradzouabi.ldapclient.ui.ldif.LdifLexer
import com.mouradzouabi.ldapclient.ui.ldif.LdifTokens
import org.junit.Assert.assertEquals
import org.junit.Test

class LdifLexerTest {
    private val sample = """
        |version: 1
        |# comment
        | folded comment
        |dn: uid=a,dc=example,dc=com
        |changetype: modify
        |replace: description
        |description: hello
        |  world
        |jpegPhoto:: /9j/4AAQ
        | SkZJRg==
        |-
        |add: seeAlso
        |seeAlso:< file:///tmp/x
        |
        |dn:: dWlkPWIsZGM9eA==
        |cn;lang-en: b
    """.trimMargin()

    private data class Token(val type: IElementType, val text: String, val state: Int, val start: Int)

    private fun lex(text: CharSequence, start: Int = 0, state: Int = 0): List<Token> {
        val lexer = LdifLexer()
        lexer.start(text, start, text.length, state)
        val tokens = mutableListOf<Token>()
        while (lexer.tokenType != null) {
            tokens += Token(lexer.tokenType!!, text.substring(lexer.tokenStart, lexer.tokenEnd), lexer.state, lexer.tokenStart)
            lexer.advance()
        }
        return tokens
    }

    @Test
    fun `tokens cover the text`() {
        val tokens = lex(sample)
        assertEquals(sample, tokens.joinToString("") { it.text })
        val types = tokens.map { it.type to it.text.trimEnd('\n') }
        assertEquals(LdifTokens.KEYWORD to "version", types[0])
        assertEquals(LdifTokens.COMMENT to " folded comment", types[4])
        assert(LdifTokens.DN_VALUE to "uid=a,dc=example,dc=com" in types)
        assert(LdifTokens.CHANGE_TYPE to "modify" in types)
        assert(LdifTokens.VALUE to "  world" in types)
        assert(LdifTokens.BASE64 to " SkZJRg==" in types)
        assert(LdifTokens.URL to "file:///tmp/x" in types)
        assert(LdifTokens.ATTRIBUTE to "cn;lang-en" in types)
        assert(LdifTokens.BASE64 to "dWlkPWIsZGM9eA==" in types)
    }

    @Test
    fun `lexing restarts consistently from any token`() {
        val full = lex(sample)
        for ((i, token) in full.withIndex()) {
            assertEquals("restart at token $i", full.drop(i), lex(sample, token.start, token.state))
        }
    }
}
