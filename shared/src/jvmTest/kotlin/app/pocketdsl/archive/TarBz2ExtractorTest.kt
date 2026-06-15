package app.pocketdsl.archive

import app.pocketdsl.db.PocketDslDatabase
import app.pocketdsl.importer.DictionaryImporter
import app.pocketdsl.storage.JvmTestDriverFactory
import app.pocketdsl.storage.SqlDelightDictionaryRepository
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream

class TarBz2ExtractorTest {
    @Test
    fun extractsValidArchiveWithOneDslFile() {
        val dslBytes = artificialDslText().toByteArray(StandardCharsets.UTF_8)
        val archive = tarBz2(file("sample.dsl", dslBytes))

        val entries = TarBz2Extractor().extract(archive)

        assertEquals(1, entries.size)
        assertEquals("sample.dsl", entries.single().path)
        assertContentEquals(dslBytes, entries.single().bytes)
    }

    @Test
    fun extractsValidArchiveWithOneDslDzFile() {
        val dzBytes = gzip(artificialDslText())
        val archive = tarBz2(file("sample.dsl.dz", dzBytes))

        val entries = TarBz2Extractor().extract(archive)

        assertEquals(1, entries.size)
        assertEquals("sample.dsl.dz", entries.single().path)
        assertContentEquals(dzBytes, entries.single().bytes)
    }

    @Test
    fun normalizesNestedSafePath() {
        val dslBytes = artificialDslText().toByteArray(StandardCharsets.UTF_8)
        val archive = tarBz2(file("./content//En-Ru.dsl", dslBytes))

        val entries = TarBz2Extractor().extract(archive)

        assertEquals("content/En-Ru.dsl", entries.single().path)
        assertContentEquals(dslBytes, entries.single().bytes)
    }

    @Test
    fun rejectsPathTraversalEntry() {
        val failure = assertFailsWith<ArchiveExtractionException> {
            TarBz2Extractor().extract(tarBz2(file("../evil.dsl", byteArrayOf(1))))
        }

        assertTrue(failure.message?.contains("path traversal") == true)
    }

    @Test
    fun rejectsAbsolutePathEntry() {
        val failure = assertFailsWith<ArchiveExtractionException> {
            TarBz2Extractor().extract(rawTarBz2File("/tmp/evil.dsl", byteArrayOf(1)))
        }

        assertTrue(failure.message?.contains("absolute path") == true)
    }

    @Test
    fun rejectsTooManyFiles() {
        val archive = tarBz2(
            file("one.dsl", byteArrayOf(1)),
            file("two.dsl", byteArrayOf(2)),
        )

        val failure = assertFailsWith<ArchiveExtractionException> {
            TarBz2Extractor(ArchiveExtractionLimits(maxFileCount = 1)).extract(archive)
        }

        assertTrue(failure.message?.contains("file count exceeds limit") == true)
    }

    @Test
    fun rejectsOversizedFile() {
        val archive = tarBz2(file("large.dsl", byteArrayOf(1, 2, 3, 4)))

        val failure = assertFailsWith<ArchiveExtractionException> {
            TarBz2Extractor(ArchiveExtractionLimits(maxFileSizeBytes = 3)).extract(archive)
        }

        assertTrue(failure.message?.contains("exceeds file size limit") == true)
    }

    @Test
    fun rejectsOversizedTotalExtractedSize() {
        val archive = tarBz2(
            file("one.dsl", byteArrayOf(1, 2, 3)),
            file("two.dsl", byteArrayOf(4, 5, 6)),
        )

        val failure = assertFailsWith<ArchiveExtractionException> {
            TarBz2Extractor(
                ArchiveExtractionLimits(maxFileSizeBytes = 10, maxTotalExtractedSizeBytes = 5),
            ).extract(archive)
        }

        assertTrue(failure.message?.contains("total extracted size exceeds limit") == true)
    }

    @Test
    fun rejectsOversizedCompressedInput() {
        val archive = tarBz2(file("sample.dsl", artificialDslText().toByteArray(StandardCharsets.UTF_8)))

        val failure = assertFailsWith<ArchiveExtractionException> {
            TarBz2Extractor(
                ArchiveExtractionLimits(maxCompressedSizeBytes = archive.size - 1L),
            ).extract(archive)
        }

        assertTrue(failure.message?.contains("compressed input exceeds limit") == true)
    }

    @Test
    fun invalidArchiveBytesFailSafely() {
        val failure = assertFailsWith<ArchiveExtractionException> {
            TarBz2Extractor().extract(byteArrayOf(0x01, 0x02, 0x03))
        }

        assertTrue(failure.message?.contains("Invalid tar.bz2 archive") == true)
    }

    @Test
    fun onlyRegularFilesAreReturned() {
        val archive = tarBz2(
            directory("content"),
            file("content/sample.dsl", byteArrayOf(1, 2, 3)),
        )

        val entries = TarBz2Extractor().extract(archive)

        assertEquals(listOf("content/sample.dsl"), entries.map { it.path })
    }

    @Test
    fun rejectsSymlinkEntries() {
        val failure = assertFailsWith<ArchiveExtractionException> {
            TarBz2Extractor().extract(tarBz2(symlink("sample.dsl", "target.dsl")))
        }

        assertTrue(failure.message?.contains("symlink") == true)
    }

    @Test
    fun rejectsHardlinkEntries() {
        val failure = assertFailsWith<ArchiveExtractionException> {
            TarBz2Extractor().extract(tarBz2(hardlink("sample.dsl", "target.dsl")))
        }

        assertTrue(failure.message?.contains("hardlink") == true)
    }

    @Test
    fun extractedDslDzBytesCanBePassedToDslDzExtractor() {
        val archive = tarBz2(file("content/sample.dsl.dz", gzip(artificialDslText())))
        val entry = TarBz2Extractor().extract(archive).single()

        val text = DslDzExtractor().extractToText(entry.bytes)

        assertEquals(artificialDslText(), text)
    }

    @Test
    fun extractedDslTextCanBePassedToDictionaryImporter() {
        val archive = tarBz2(
            file("content/sample.dsl", artificialDslText().toByteArray(StandardCharsets.UTF_8)),
        )
        val entry = TarBz2Extractor().extract(archive).single()
        val repository = SqlDelightDictionaryRepository(
            PocketDslDatabase(JvmTestDriverFactory().createDriver()),
        )
        val importer = DictionaryImporter(repository, currentTimeMillis = { 42 })

        val result = importer.importDslText(
            sourceFileName = entry.path,
            text = String(entry.bytes, StandardCharsets.UTF_8),
        )

        assertEquals("Artificial Archive", result.dictionaryName)
        assertEquals("en-ru", result.direction)
        assertEquals(2, result.entryCount)
        assertEquals(listOf("alpha"), repository.suggest("a", limit = 10).map { it.headword })
    }

    private fun artificialDslText(): String =
        """
            #NAME "Artificial Archive"
            #INDEX_LANGUAGE "English"
            #CONTENTS_LANGUAGE "Russian"

            alpha
             [trn]альфа[/trn]
            beta
             [trn]бета[/trn]
        """.trimIndent()

    private fun gzip(text: String): ByteArray =
        gzip(text.toByteArray(StandardCharsets.UTF_8))

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            gzip.write(bytes)
        }
        return output.toByteArray()
    }

    private fun file(path: String, bytes: ByteArray): TestTarEntry =
        TestTarEntry.File(path, bytes)

    private fun directory(path: String): TestTarEntry =
        TestTarEntry.Directory(path)

    private fun symlink(path: String, target: String): TestTarEntry =
        TestTarEntry.Link(path = path, target = target, typeFlag = TarConstants.LF_SYMLINK)

    private fun hardlink(path: String, target: String): TestTarEntry =
        TestTarEntry.Link(path = path, target = target, typeFlag = TarConstants.LF_LINK)

    private fun tarBz2(vararg entries: TestTarEntry): ByteArray {
        val output = ByteArrayOutputStream()
        BZip2CompressorOutputStream(output).use { bzipOutput ->
            TarArchiveOutputStream(bzipOutput).use { tarOutput ->
                tarOutput.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                entries.forEach { entry ->
                    when (entry) {
                        is TestTarEntry.Directory -> {
                            val tarEntry = TarArchiveEntry("${entry.path.trimEnd('/')}/")
                            tarOutput.putArchiveEntry(tarEntry)
                            tarOutput.closeArchiveEntry()
                        }

                        is TestTarEntry.File -> {
                            val tarEntry = TarArchiveEntry(entry.path)
                            tarEntry.size = entry.bytes.size.toLong()
                            tarOutput.putArchiveEntry(tarEntry)
                            tarOutput.write(entry.bytes)
                            tarOutput.closeArchiveEntry()
                        }

                        is TestTarEntry.Link -> {
                            val tarEntry = TarArchiveEntry(entry.path, entry.typeFlag)
                            tarEntry.linkName = entry.target
                            tarOutput.putArchiveEntry(tarEntry)
                            tarOutput.closeArchiveEntry()
                        }
                    }
                }
            }
        }
        return output.toByteArray()
    }

    private fun rawTarBz2File(path: String, bytes: ByteArray): ByteArray {
        val tarBytes = ByteArrayOutputStream()
        val header = ByteArray(TAR_BLOCK_SIZE)
        writeAscii(header, offset = 0, length = 100, value = path)
        writeOctal(header, offset = 100, length = 8, value = 0b110100100L)
        writeOctal(header, offset = 108, length = 8, value = 0)
        writeOctal(header, offset = 116, length = 8, value = 0)
        writeOctal(header, offset = 124, length = 12, value = bytes.size.toLong())
        writeOctal(header, offset = 136, length = 12, value = 0)
        for (index in 148 until 156) {
            header[index] = ' '.code.toByte()
        }
        header[156] = TarConstants.LF_NORMAL
        writeAscii(header, offset = 257, length = 6, value = "ustar")
        writeAscii(header, offset = 263, length = 2, value = "00")

        val checksum = header.sumOf { it.toUByte().toInt() }
        writeChecksum(header, checksum)

        tarBytes.write(header)
        tarBytes.write(bytes)
        val remainder = bytes.size % TAR_BLOCK_SIZE
        if (remainder != 0) {
            tarBytes.write(ByteArray(TAR_BLOCK_SIZE - remainder))
        }
        tarBytes.write(ByteArray(TAR_BLOCK_SIZE * 2))

        return bzip2(tarBytes.toByteArray())
    }

    private fun bzip2(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        BZip2CompressorOutputStream(output).use { bzipOutput ->
            bzipOutput.write(bytes)
        }
        return output.toByteArray()
    }

    private fun writeAscii(target: ByteArray, offset: Int, length: Int, value: String) {
        val encoded = value.toByteArray(StandardCharsets.US_ASCII)
        require(encoded.size <= length) { "tar header field is too small for '$value'" }
        encoded.copyInto(target, destinationOffset = offset)
    }

    private fun writeOctal(target: ByteArray, offset: Int, length: Int, value: Long) {
        val encoded = value.toString(radix = 8).toByteArray(StandardCharsets.US_ASCII)
        require(encoded.size < length) { "tar octal field is too small for $value" }
        val start = offset + length - encoded.size - 1
        for (index in offset until start) {
            target[index] = '0'.code.toByte()
        }
        encoded.copyInto(target, destinationOffset = start)
    }

    private fun writeChecksum(target: ByteArray, checksum: Int) {
        val encoded = checksum.toString(radix = 8)
            .padStart(length = 6, padChar = '0')
            .toByteArray(StandardCharsets.US_ASCII)
        encoded.copyInto(target, destinationOffset = 148)
        target[154] = 0
        target[155] = ' '.code.toByte()
    }

    private sealed interface TestTarEntry {
        data class File(val path: String, val bytes: ByteArray) : TestTarEntry
        data class Directory(val path: String) : TestTarEntry
        data class Link(val path: String, val target: String, val typeFlag: Byte) : TestTarEntry
    }

    private companion object {
        const val TAR_BLOCK_SIZE = 512
    }
}
