package com.mouradzouabi.ldapclient.core

import com.unboundid.util.StaticUtils
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

enum class AttributeKind(val inlineEditable: Boolean) {
    TEXT(true), DN(true), INTEGER(true), BOOLEAN(true), TIME(true),
    PASSWORD(false), BINARY(false), CERTIFICATE(false), IMAGE(false), GUID(false), SID(false);
}

/** Decides how attribute values are displayed and which editor is used. */
object AttributeKinds {
    private const val SYNTAX = "1.3.6.1.4.1.1466.115.121.1."

    private val PASSWORD_NAMES = setOf(
        "userpassword", "unicodepwd", "sambantpassword", "sambalmpassword", "authpassword", "olcrootpw",
        "ds-cfg-root-password", "userpkcs12",
    )
    private val CERT_NAMES = setOf("usercertificate", "cacertificate", "crosscertificatepair", "usersmimecertificate")
    private val IMAGE_NAMES = setOf("jpegphoto", "photo", "thumbnailphoto", "thumbnaillogo")
    private val BINARY_NAMES = setOf(
        "objectguid", "objectsid", "msexchmailboxguid", "msexchmailboxsecuritydescriptor", "ntsecuritydescriptor",
        "logonhours", "tokengroups", "sidhistory", "krbprincipalkey", "audio", "certificaterevocationlist",
        "authorityrevocationlist", "deltarevocationlist", "supportedalgorithms", "msds-generationid",
    )
    private val DN_NAMES = setOf(
        "member", "uniquemember", "manager", "seealso", "owner", "memberof", "secretary", "roleoccupant",
        "modifiersname", "creatorsname", "distinguishedname", "ismemberof", "directreports", "managedby",
        "subschemasubentry", "entrydn", "pwdpolicysubentry", "aliasedobjectname",
    )

    fun classify(name: String, schema: LdapSchema, values: List<ByteArray> = emptyList()): AttributeKind {
        val base = LdapSchema.baseName(name).lowercase()
        if (base in PASSWORD_NAMES) return AttributeKind.PASSWORD
        if (base == "objectguid" || (base.endsWith("guid") && values.all { it.size == 16 } && values.isNotEmpty())) return AttributeKind.GUID
        if (base == "objectsid" || base == "sidhistory") return AttributeKind.SID
        val syntax = schema.syntaxOid(name)
        if (base in CERT_NAMES || syntax == SYNTAX + "8") return AttributeKind.CERTIFICATE
        if (base in IMAGE_NAMES || syntax == SYNTAX + "28") return AttributeKind.IMAGE
        if (base in BINARY_NAMES || name.contains(";binary", ignoreCase = true) ||
            syntax in setOf(SYNTAX + "5", SYNTAX + "9", SYNTAX + "10", SYNTAX + "49")
        ) return AttributeKind.BINARY
        if (values.any { !isText(it) }) return AttributeKind.BINARY
        return when {
            syntax == SYNTAX + "12" || base in DN_NAMES -> AttributeKind.DN
            syntax == SYNTAX + "7" -> AttributeKind.BOOLEAN
            syntax == SYNTAX + "27" -> AttributeKind.INTEGER
            syntax == SYNTAX + "24" || syntax == SYNTAX + "53" || base.endsWith("timestamp") -> AttributeKind.TIME
            else -> AttributeKind.TEXT
        }
    }

    fun isText(bytes: ByteArray): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            return false
        }
        return text.none { it.code < 0x20 && it != '\n' && it != '\r' && it != '\t' }
    }

    /** Single-line presentation of a value (never reveals passwords). */
    fun display(kind: AttributeKind, bytes: ByteArray): String = when (kind) {
        AttributeKind.PASSWORD -> {
            val text = if (isText(bytes)) String(bytes, Charsets.UTF_8) else ""
            val scheme = Regex("^\\{([A-Za-z0-9.\\-_]+)}").find(text)?.groupValues?.get(1)
            if (scheme != null) "{$scheme} ••••••••" else "•••••••• (${bytes.size} bytes)"
        }
        AttributeKind.GUID -> if (bytes.size == 16) formatGuid(bytes) else hexPreview(bytes)
        AttributeKind.SID -> formatSid(bytes) ?: hexPreview(bytes)
        AttributeKind.CERTIFICATE -> certificateSummary(bytes) ?: "Binary (${bytes.size} bytes)"
        AttributeKind.IMAGE -> "${imageType(bytes) ?: "Image"} (${bytes.size} bytes)"
        AttributeKind.BINARY -> "Binary (${bytes.size} bytes) ${hexPreview(bytes, 16)}"
        AttributeKind.TIME -> {
            val text = String(bytes, Charsets.UTF_8)
            formatTime(text)?.let { "$text  ($it)" } ?: text
        }
        else -> String(bytes, Charsets.UTF_8).replace("\r\n", "⏎").replace("\n", "⏎")
    }

    fun formatTime(generalized: String): String? = try {
        val date = StaticUtils.decodeGeneralizedTime(generalized)
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(date.toInstant())
    } catch (_: Exception) {
        null
    }

    fun hexPreview(bytes: ByteArray, max: Int = 32): String {
        val shown = bytes.take(max).joinToString(" ") { "%02x".format(it) }
        return if (bytes.size > max) "$shown …" else shown
    }

    fun formatGuid(b: ByteArray): String {
        fun h(i: Int) = "%02x".format(b[i])
        return buildString {
            append(h(3)).append(h(2)).append(h(1)).append(h(0)).append('-')
            append(h(5)).append(h(4)).append('-')
            append(h(7)).append(h(6)).append('-')
            append(h(8)).append(h(9)).append('-')
            for (i in 10..15) append(h(i))
        }
    }

    fun formatSid(b: ByteArray): String? {
        if (b.size < 8) return null
        val count = b[1].toInt() and 0xff
        if (b.size < 8 + count * 4) return null
        var authority = 0L
        for (i in 2..7) authority = (authority shl 8) or (b[i].toLong() and 0xff)
        val sb = StringBuilder("S-").append(b[0].toInt() and 0xff).append('-').append(authority)
        val buffer = ByteBuffer.wrap(b, 8, count * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(count) { sb.append('-').append(buffer.int.toLong() and 0xffffffffL) }
        return sb.toString()
    }

    fun parseCertificate(bytes: ByteArray): X509Certificate? = try {
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    } catch (_: Exception) {
        null
    }

    private fun certificateSummary(bytes: ByteArray): String? =
        parseCertificate(bytes)?.let { "X.509: ${it.subjectX500Principal.name} (expires ${it.notAfter})" }

    fun imageType(bytes: ByteArray): String? = when {
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "JPEG image"
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "PNG image"
        bytes.size > 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "GIF image"
        else -> null
    }
}
