package app.pocketdsl.archive

import app.pocketdsl.db.PocketDslDatabase
import app.pocketdsl.importer.DictionaryImporter
import app.pocketdsl.storage.JvmTestDriverFactory
import app.pocketdsl.storage.SqlDelightDictionaryRepository
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DslDzExtractorTest {
    @Test
    fun extractsGzipCompressedDslText() {
        val text = artificialDslText()
        val compressed = gzip(text)

        val extracted = DslDzExtractor().extractToText(compressed)

        assertEquals(text, extracted)
    }

    @Test
    fun extractedTextCanBeImportedByDictionaryImporter() {
        val text = artificialDslText()
        val extracted = DslDzExtractor().extractToText(gzip(text))
        val repository = SqlDelightDictionaryRepository(
            PocketDslDatabase(JvmTestDriverFactory().createDriver()),
        )
        val importer = DictionaryImporter(repository, currentTimeMillis = { 42 })

        val result = importer.importDslText(
            sourceFileName = "artificial.dsl",
            text = extracted,
        )

        assertEquals("Artificial Dz", result.dictionaryName)
        assertEquals("en-ru", result.direction)
        assertEquals(2, result.entryCount)
        assertEquals(listOf("alpha"), repository.suggest("a", limit = 10).map { it.headword })
        assertEquals("beta", repository.lookupExact("BETA").single().headword)
    }

    @Test
    fun rejectsOversizedCompressedInput() {
        val compressed = gzip(artificialDslText())
        val extractor = DslDzExtractor(
            DslDzLimits(maxCompressedInputBytes = compressed.size - 1L),
        )

        val failure = assertFailsWith<DslDzExtractionException> {
            extractor.extractToText(compressed)
        }

        assertTrue(failure.message?.contains("compressed input exceeds limit") == true)
    }

    @Test
    fun rejectsOversizedDecompressedOutput() {
        val text = artificialDslText()
        val extractor = DslDzExtractor(
            DslDzLimits(maxDecompressedOutputBytes = text.toByteArray(StandardCharsets.UTF_8).size - 1L),
        )

        val failure = assertFailsWith<DslDzExtractionException> {
            extractor.extractToText(gzip(text))
        }

        assertTrue(failure.message?.contains("decompressed output exceeds limit") == true)
    }

    @Test
    fun rejectsInvalidInputBytes() {
        val failure = assertFailsWith<DslDzExtractionException> {
            DslDzExtractor().extractToText(byteArrayOf(0x01, 0x02, 0x03))
        }

        assertTrue(failure.message?.contains("Invalid DSL.DZ gzip data") == true)
    }

    @Test
    fun rejectsInvalidUtf8Text() {
        val failure = assertFailsWith<DslDzExtractionException> {
            DslDzExtractor().extractToText(gzip(byteArrayOf(0xC3.toByte())))
        }

        assertTrue(failure.message?.contains("not valid UTF-8") == true)
    }

    private fun artificialDslText(): String =
        """
            #NAME "Artificial Dz"
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
}
