package app.pocketdsl.importer

import app.pocketdsl.archive.DslDzExtractor
import app.pocketdsl.archive.TarBz2Extractor
import app.pocketdsl.db.PocketDslDatabase
import app.pocketdsl.storage.DictionaryRepository
import app.pocketdsl.storage.JvmTestDriverFactory
import app.pocketdsl.storage.SqlDelightDictionaryRepository
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream

class DictionaryPackageImporterTest {
    @Test
    fun packageWithOneDslFileImportsSuccessfully() {
        val repository = newRepository()
        val importer = newPackageImporter(repository)
        val archive = tarBz2(
            file("content/sample.dsl", dslText("Plain Dictionary", "alpha" to "альфа")),
        )

        val result = importer.importTarBz2Package("package.tar.bz2", archive)

        assertTrue(result.isSuccess)
        assertEquals(1, result.totalImportedDictionaries)
        assertEquals(1, result.totalImportedEntries)
        assertEquals(0, result.totalSkippedEntries)
        assertEquals(0, result.failedDictionaryCount)
        assertEquals("Plain Dictionary", result.dictionaries.single().importResult?.dictionaryName)
        assertEquals("alpha", repository.lookupExact("alpha").single().headword)
    }

    @Test
    fun packageWithOneDslDzFileImportsSuccessfully() {
        val repository = newRepository()
        val importer = newPackageImporter(repository)
        val archive = tarBz2(
            file("content/compressed.dsl.dz", gzip(dslText("Compressed Dictionary", "beta" to "бета"))),
        )

        val result = importer.importTarBz2Package("package.tar.bz2", archive)

        assertTrue(result.isSuccess)
        assertEquals(1, result.totalImportedDictionaries)
        assertEquals(1, result.totalImportedEntries)
        assertEquals(PackageDictionaryKind.DSL_DZ, result.dictionaries.single().kind)
        assertEquals("Compressed Dictionary", repository.selectDictionaries().single().name)
        assertEquals("beta", repository.lookupExact("BETA").single().headword)
    }

    @Test
    fun packageWithDslAndDslDzImportsBoth() {
        val repository = newRepository()
        val importer = newPackageImporter(repository)
        val archive = tarBz2(
            file("content/plain.dsl", dslText("Plain Dictionary", "alpha" to "альфа")),
            file("content/compressed.dsl.dz", gzip(dslText("Compressed Dictionary", "beta" to "бета"))),
        )

        val result = importer.importTarBz2Package("package.tar.bz2", archive)

        assertEquals(2, result.totalImportedDictionaries)
        assertEquals(2, result.totalImportedEntries)
        assertEquals(0, result.failedDictionaryCount)
        assertEquals(listOf("Compressed Dictionary", "Plain Dictionary"), repository.selectDictionaries().map { it.name })
        assertEquals("alpha", repository.lookupExact("alpha").single().headword)
        assertEquals("beta", repository.lookupExact("beta").single().headword)
    }

    @Test
    fun unsupportedFilesAreIgnoredAndCounted() {
        val repository = newRepository()
        val importer = newPackageImporter(repository)
        val archive = tarBz2(
            file("content/sample.dsl", dslText("Dictionary", "alpha" to "альфа")),
            file("content/sample.ann", "annotation"),
            file("content/image.bmp", "bitmap"),
            file("content/readme.rtf", "{rtf}"),
            file("content/readme.txt", "text"),
            file("content/Speech.lsa", "speech"),
            file("content/morphology.aff", "affix"),
        )

        val result = importer.importTarBz2Package("package.tar.bz2", archive)

        assertEquals(1, result.totalImportedDictionaries)
        assertEquals(6, result.ignoredFileCount)
        assertEquals(1, repository.selectDictionaries().size)
    }

    @Test
    fun dictionaryImportOrderIsDeterministicByPath() {
        val repository = newRepository()
        val importer = newPackageImporter(repository)
        val archive = tarBz2(
            file("z-last.dsl", dslText("Last", "zulu" to "зулу")),
            file("a-first.dsl", dslText("First", "alpha" to "альфа")),
            file("m-middle.dsl.dz", gzip(dslText("Middle", "mike" to "майк"))),
        )

        val result = importer.importTarBz2Package("package.tar.bz2", archive)

        assertEquals(
            listOf("a-first.dsl", "m-middle.dsl.dz", "z-last.dsl"),
            result.dictionaries.map { it.path },
        )
        assertEquals(listOf("First", "Middle", "Last"), result.dictionaries.map { it.importResult?.dictionaryName })
    }

    @Test
    fun brokenDslDzDoesNotPreventValidDslImport() {
        val repository = newRepository()
        val importer = newPackageImporter(repository)
        val archive = tarBz2(
            file("broken.dsl.dz", byteArrayOf(0x01, 0x02, 0x03)),
            file("valid.dsl", dslText("Valid", "alpha" to "альфа")),
        )

        val result = importer.importTarBz2Package("package.tar.bz2", archive)

        assertTrue(result.isSuccess)
        assertEquals(1, result.totalImportedDictionaries)
        assertEquals(1, result.failedDictionaryCount)
        assertEquals(listOf("broken.dsl.dz", "valid.dsl"), result.dictionaries.map { it.path })
        assertNotNull(result.dictionaries.first().failure)
        assertEquals("Valid", repository.selectDictionaries().single().name)
        assertEquals("alpha", repository.lookupExact("alpha").single().headword)
    }

    @Test
    fun archiveLevelInvalidBytesFailSafely() {
        val repository = newRepository()
        val importer = newPackageImporter(repository)
        val events = mutableListOf<PackageImportProgress>()

        val result = importer.importTarBz2Package(
            sourceFileName = "invalid.tar.bz2",
            bytes = byteArrayOf(0x01, 0x02, 0x03),
            progress = events::add,
        )

        assertTrue(!result.isSuccess)
        assertNotNull(result.archiveFailure)
        assertEquals(0, result.totalImportedDictionaries)
        assertEquals(0, result.failedDictionaryCount)
        assertTrue(repository.selectDictionaries().isEmpty())
        assertIs<PackageImportProgress.Failed>(events.last())
    }

    @Test
    fun exactLookupWorksAfterPackageImport() {
        val repository = newRepository()
        val importer = newPackageImporter(repository)
        val archive = tarBz2(
            file("content/sample.dsl", dslText("Lookup", "apple" to "яблоко")),
        )

        importer.importTarBz2Package("package.tar.bz2", archive)

        val entry = repository.lookupExact("APPLE").single()
        assertEquals("apple", entry.headword)
        assertTrue(entry.articleHtml.contains("яблоко"))
    }

    @Test
    fun suggestionsWorkAfterPackageImport() {
        val repository = newRepository()
        val importer = newPackageImporter(repository)
        val archive = tarBz2(
            file(
                "content/sample.dsl",
                dslText(
                    "Suggestions",
                    "apple" to "яблоко",
                    "apricot" to "абрикос",
                    "banana" to "банан",
                ),
            ),
        )

        importer.importTarBz2Package("package.tar.bz2", archive)

        assertEquals(
            listOf("apple", "apricot"),
            repository.suggest("ap", limit = 10).map { it.headword },
        )
    }

    @Test
    fun progressCallbackReceivesPackageLevelEvents() {
        val repository = newRepository()
        val importer = newPackageImporter(repository)
        val archive = tarBz2(
            file("content/sample.dsl", dslText("Progress", "alpha" to "альфа")),
        )
        val events = mutableListOf<PackageImportProgress>()

        importer.importTarBz2Package(
            sourceFileName = "package.tar.bz2",
            bytes = archive,
            progress = events::add,
        )

        assertIs<PackageImportProgress.Started>(events[0])
        assertTrue(events.any { it is PackageImportProgress.ExtractingArchive })
        assertTrue(events.any { it is PackageImportProgress.ArchiveExtracted })
        assertTrue(events.any { it is PackageImportProgress.ImportingDictionary })
        assertTrue(events.any { it is PackageImportProgress.DictionaryImported })
        assertIs<PackageImportProgress.Completed>(events.last())
    }

    private fun newPackageImporter(repository: DictionaryRepository): DictionaryPackageImporter =
        DictionaryPackageImporter(
            archiveExtractor = TarBz2Extractor(),
            dslDzExtractor = DslDzExtractor(),
            dictionaryImporter = DictionaryImporter(repository, currentTimeMillis = { 42 }),
        )

    private fun newRepository(): DictionaryRepository {
        val driver = JvmTestDriverFactory().createDriver()
        return SqlDelightDictionaryRepository(PocketDslDatabase(driver))
    }

    private fun dslText(name: String, vararg entries: Pair<String, String>): ByteArray =
        buildString {
            appendLine("#NAME \"$name\"")
            appendLine("#INDEX_LANGUAGE \"English\"")
            appendLine("#CONTENTS_LANGUAGE \"Russian\"")
            entries.forEach { (headword, article) ->
                appendLine()
                appendLine(headword)
                appendLine(" [trn]$article[/trn]")
            }
        }.trimEnd().toByteArray(StandardCharsets.UTF_8)

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            gzip.write(bytes)
        }
        return output.toByteArray()
    }

    private fun file(path: String, bytes: ByteArray): TestTarEntry =
        TestTarEntry(path, bytes)

    private fun file(path: String, text: String): TestTarEntry =
        file(path, text.toByteArray(StandardCharsets.UTF_8))

    private fun tarBz2(vararg entries: TestTarEntry): ByteArray {
        val output = ByteArrayOutputStream()
        BZip2CompressorOutputStream(output).use { bzipOutput ->
            TarArchiveOutputStream(bzipOutput).use { tarOutput ->
                tarOutput.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                entries.forEach { entry ->
                    val tarEntry = TarArchiveEntry(entry.path)
                    tarEntry.size = entry.bytes.size.toLong()
                    tarOutput.putArchiveEntry(tarEntry)
                    tarOutput.write(entry.bytes)
                    tarOutput.closeArchiveEntry()
                }
            }
        }
        return output.toByteArray()
    }

    private data class TestTarEntry(val path: String, val bytes: ByteArray)
}
