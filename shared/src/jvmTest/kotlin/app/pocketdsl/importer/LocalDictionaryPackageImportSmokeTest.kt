package app.pocketdsl.importer

import app.pocketdsl.archive.DslDzExtractor
import app.pocketdsl.archive.TarBz2Extractor
import app.pocketdsl.db.PocketDslDatabase
import app.pocketdsl.storage.SqlDelightDictionaryRepository
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalDictionaryPackageImportSmokeTest {
    @Test
    fun importsLocalDictionaryPackageWhenPresent() {
        val archivePath = configuredArchivePath()

        if (!Files.exists(archivePath)) {
            println(
                "Skipping local dictionary import smoke test: " +
                    "${archivePath.toAbsolutePath()} does not exist. " +
                    "Place a local .tar.bz2 dictionary archive there to run this smoke test.",
            )
            return
        }

        require(Files.isRegularFile(archivePath)) {
            "Local dictionary archive path is not a file: ${archivePath.toAbsolutePath()}"
        }

        val driver = app.pocketdsl.storage.JvmTestDriverFactory().createDriver()
        try {
            val repository = SqlDelightDictionaryRepository(PocketDslDatabase(driver))
            val importer = DictionaryPackageImporter(
                archiveExtractor = TarBz2Extractor(),
                dslDzExtractor = DslDzExtractor(),
                dictionaryImporter = DictionaryImporter(repository),
            )

            println("Running local dictionary import smoke test")
            println("Archive: ${archivePath.toAbsolutePath()}")

            val archiveBytes = Files.readAllBytes(archivePath)
            val result = importer.importTarBz2Package(
                sourceFileName = archivePath.fileName.toString(),
                bytes = archiveBytes,
            )

            printSummary(result)

            assertTrue(result.isSuccess, result.archiveFailure?.message ?: "Archive import failed")
            assertTrue(
                result.totalImportedDictionaries > 0,
                "Expected at least one dictionary to be imported from ${archivePath.fileName}",
            )
            assertTrue(
                result.totalImportedEntries > 0,
                "Expected at least one entry to be imported from ${archivePath.fileName}",
            )
            assertEquals(0, result.failedDictionaryCount, "Expected all dictionary files to import successfully")

            verifyExpectedLookupIfPresent(repository)
        } finally {
            driver.close()
        }
    }

    private fun configuredArchivePath(): Path =
        Paths.get(
            System.getProperty(
                "pocketdsl.localDictionaryArchive",
                "dict-example/enruen-content-1.1.tar.bz2",
            ),
        )

    private fun printSummary(result: PackageImportResult) {
        println("Import summary:")
        println("  imported dictionary count: ${result.totalImportedDictionaries}")
        println("  imported entry count: ${result.totalImportedEntries}")
        println("  failed dictionary count: ${result.failedDictionaryCount}")
        println("  ignored file count: ${result.ignoredFileCount}")
        println("Imported dictionaries:")
        result.dictionaries.forEach { dictionary ->
            val importResult = dictionary.importResult
            val status = if (dictionary.isSuccess) "imported" else "failed"
            val entries = importResult?.entryCount ?: 0
            val skipped = importResult?.skippedCount ?: 0
            println(
                "  $status ${dictionary.kind} ${dictionary.path} " +
                    "(entries=$entries, skipped=$skipped)",
            )
            dictionary.failure?.let { failure ->
                println("    failure: ${failure.message}")
            }
        }
    }

    private fun verifyExpectedLookupIfPresent(repository: SqlDelightDictionaryRepository) {
        val candidates = listOf("a", "A", "абажур")
        val matches = candidates.mapNotNull { query ->
            val entries = repository.lookupExact(query)
            if (entries.isEmpty()) {
                null
            } else {
                query to entries.map { it.headword }.distinct()
            }
        }

        if (matches.isEmpty()) {
            println("Expected lookup probes not found: ${candidates.joinToString()}")
            println("Archive import succeeded, but this archive may not contain those probe headwords.")
            return
        }

        println("Lookup probes:")
        matches.forEach { (query, headwords) ->
            println("  $query -> ${headwords.joinToString(limit = 5)}")
        }
        assertTrue(matches.isNotEmpty())
    }
}
