package com.mouradzouabi.ldapclient.core

import com.unboundid.ldap.sdk.Entry
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.Modification
import com.unboundid.ldap.sdk.ModificationType
import com.unboundid.ldap.sdk.ResultCode
import com.unboundid.ldif.DuplicateValueBehavior
import com.unboundid.ldif.LDIFAddChangeRecord
import com.unboundid.ldif.LDIFChangeRecord
import com.unboundid.ldif.LDIFDeleteChangeRecord
import com.unboundid.ldif.LDIFException
import com.unboundid.ldif.LDIFModifyChangeRecord
import com.unboundid.ldif.LDIFModifyDNChangeRecord
import com.unboundid.ldif.LDIFReader
import com.unboundid.ldif.LDIFRecord
import com.unboundid.ldif.TrailingSpaceBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.FilterReader
import java.io.Reader

/** One raw LDIF record: its lines (comments removed) and the 0-based line where it starts in the source. */
data class LdifChunk(val index: Int, val line: Int, val lines: List<String>)

enum class ContentRecordMode(val label: String) {
    ADD("Add (fail if the entry exists)"),
    ADD_OR_REPLACE("Add, or replace attributes of existing entries"),
    ;

    override fun toString() = label
}

data class LdifRunOptions(
    val contentMode: ContentRecordMode = ContentRecordMode.ADD,
    val continueOnError: Boolean = true,
    val dryRun: Boolean = false,
)

enum class LdifStatus { OK, FAILED, VALIDATED }

data class LdifRecordResult(
    val index: Int,
    /** 0-based line of the record in the source. */
    val line: Int,
    val operation: String,
    val dn: String,
    val status: LdifStatus,
    val message: String? = null,
    /** New DN for successful modrdn/moddn records. */
    val newDn: String? = null,
)

data class LdifRunSummary(val processed: Int, val succeeded: Int, val failed: Int, val stoppedOnError: Boolean)

/**
 * Streams LDIF records (content records and change records: add / modify / delete / modrdn) and
 * applies them one at a time, so arbitrarily large files never need to be held in memory.
 */
object LdifScript {

    /** Splits [reader] into records separated by blank lines, dropping comments and the `version:` line. */
    fun chunks(reader: BufferedReader): Sequence<LdifChunk> = sequence {
        var lineNo = -1
        var current = ArrayList<String>()
        var start = -1
        var inComment = false
        var index = 0
        var first = true
        while (true) {
            var line = reader.readLine() ?: break
            lineNo++
            if (lineNo == 0 && line.startsWith('\uFEFF')) line = line.substring(1)
            if (line.isBlank()) {
                inComment = false
                if (current.isNotEmpty()) {
                    yield(LdifChunk(index++, start, current))
                    current = ArrayList()
                }
                continue
            }
            if (line.startsWith('#')) {
                inComment = true
                continue
            }
            if (line.startsWith(' ')) {
                if (inComment) continue
            } else {
                inComment = false
            }
            if (first && current.isEmpty() && line.startsWith("version:", ignoreCase = true)) {
                first = false
                continue
            }
            first = false
            if (current.isEmpty()) start = lineNo
            current += line
        }
        if (current.isNotEmpty()) yield(LdifChunk(index, start, current))
    }

    fun parse(chunk: LdifChunk): LDIFRecord = LDIFReader.decodeLDIFRecord(
        DuplicateValueBehavior.STRIP,
        TrailingSpaceBehavior.STRIP,
        null,
        *chunk.lines.toTypedArray(),
    )

    fun operationOf(record: LDIFRecord, mode: ContentRecordMode): String = when (record) {
        is Entry -> if (mode == ContentRecordMode.ADD) "add" else "add/replace"
        is LDIFAddChangeRecord -> "add"
        is LDIFModifyChangeRecord -> "modify"
        is LDIFDeleteChangeRecord -> "delete"
        is LDIFModifyDNChangeRecord -> "moddn"
        is LDIFChangeRecord -> record.changeType.name.lowercase()
        else -> "?"
    }

    /**
     * Applies every record read from [reader] using [session]. Results are reported through [onResult]
     * (called on a background thread). Cancellation is checked between records.
     */
    suspend fun run(
        session: LdapSession,
        reader: Reader,
        options: LdifRunOptions,
        onResult: (LdifRecordResult) -> Unit,
    ): LdifRunSummary = withContext(Dispatchers.IO) {
        var processed = 0
        var succeeded = 0
        var failed = 0
        val buffered = reader as? BufferedReader ?: BufferedReader(reader)
        buffered.use {
            for (chunk in chunks(buffered)) {
                ensureActive()
                val result = runChunk(session, chunk, options)
                processed++
                if (result.status == LdifStatus.FAILED) failed++ else succeeded++
                onResult(result)
                if (result.status == LdifStatus.FAILED && !options.continueOnError) {
                    return@withContext LdifRunSummary(processed, succeeded, failed, stoppedOnError = true)
                }
            }
        }
        LdifRunSummary(processed, succeeded, failed, stoppedOnError = false)
    }

    private suspend fun runChunk(session: LdapSession, chunk: LdifChunk, options: LdifRunOptions): LdifRecordResult {
        val record = try {
            parse(chunk)
        } catch (e: LDIFException) {
            val dn = chunk.lines.firstOrNull { it.startsWith("dn:", ignoreCase = true) }?.substringAfter(':')?.trim().orEmpty()
            val line = if (e.lineNumber in 0 until chunk.lines.size) chunk.line + e.lineNumber.toInt() else chunk.line
            return LdifRecordResult(chunk.index, line, "parse", dn, LdifStatus.FAILED, e.exceptionMessage)
        }
        val operation = operationOf(record, options.contentMode)
        val dn = record.dn
        if (options.dryRun) return LdifRecordResult(chunk.index, chunk.line, operation, dn, LdifStatus.VALIDATED, "Syntax OK")
        return try {
            var effective = operation
            when (record) {
                is Entry -> effective = applyEntry(session, record, options.contentMode)
                is LDIFChangeRecord -> session.applyChange(record)
            }
            val newDn = (record as? LDIFModifyDNChangeRecord)?.newDN?.toString()
            LdifRecordResult(chunk.index, chunk.line, effective, dn, LdifStatus.OK, null, newDn)
        } catch (e: LDAPException) {
            LdifRecordResult(chunk.index, chunk.line, operation, dn, LdifStatus.FAILED, LdapErrors.describe(e))
        }
    }

    /** Adds a content record; in [ContentRecordMode.ADD_OR_REPLACE] existing entries get their attributes replaced. */
    private suspend fun applyEntry(session: LdapSession, entry: Entry, mode: ContentRecordMode): String {
        try {
            session.add(entry)
            return "add"
        } catch (e: LDAPException) {
            if (mode != ContentRecordMode.ADD_OR_REPLACE || e.resultCode != ResultCode.ENTRY_ALREADY_EXISTS) throw e
        }
        // objectClass values are only added: replacing them would drop superclasses many servers add implicitly.
        val existingClasses = session.getEntry(entry.dn, listOf("objectClass"))?.objectClassValues.orEmpty()
            .map { it.lowercase() }.toSet()
        val mods = entry.attributes.mapNotNull { attr ->
            if (attr.name.equals("objectClass", ignoreCase = true)) {
                val missing = attr.values.filter { it.lowercase() !in existingClasses }
                if (missing.isEmpty()) null else Modification(ModificationType.ADD, attr.name, *missing.toTypedArray())
            } else {
                Modification(ModificationType.REPLACE, attr.name, *attr.valueByteArrays)
            }
        }
        if (mods.isNotEmpty()) session.modify(entry.dn, mods)
        return "replace"
    }
}

/** Counts characters read, for progress reporting. */
class CountingReader(reader: Reader) : FilterReader(reader) {
    @Volatile
    var count: Long = 0
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(cbuf: CharArray, off: Int, len: Int): Int = super.read(cbuf, off, len).also { if (it > 0) count += it }

    override fun skip(n: Long): Long = super.skip(n).also { count += it }
}
