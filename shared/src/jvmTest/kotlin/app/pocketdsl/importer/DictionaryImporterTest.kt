package app.pocketdsl.importer

import app.pocketdsl.db.PocketDslDatabase
import app.pocketdsl.storage.DictionaryEntry
import app.pocketdsl.storage.DictionaryRepository
import app.pocketdsl.storage.DictionaryMetadata
import app.pocketdsl.storage.DictionarySuggestion
import app.pocketdsl.storage.HistoryItem
import app.pocketdsl.storage.JvmTestDriverFactory
import app.pocketdsl.storage.NewDictionaryEntry
import app.pocketdsl.storage.NewDictionaryMetadata
import app.pocketdsl.storage.SqlDelightDictionaryRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DictionaryImporterTest {
    @Test
    fun importsEnglishRussianDslTextAndIndexesEntries() {
        val repository = newRepository()
        val importer = DictionaryImporter(repository, currentTimeMillis = { 42 })

        val result = importer.importDslText(
            sourceFileName = "sample-en-ru.dsl",
            text = """
                #NAME "Artificial En-Ru"
                #INDEX_LANGUAGE "English"
                #CONTENTS_LANGUAGE "Russian"

                apple
                 [trn][m1]яблоко[/m][/trn]
                apricot
                 [trn]абрикос[/trn]
            """.trimIndent(),
        )

        assertEquals("Artificial En-Ru", result.dictionaryName)
        assertEquals("en-ru", result.direction)
        assertEquals(2, result.entryCount)
        assertEquals(0, result.skippedCount)
        assertEquals("sample-en-ru.dsl", result.sourceFileName)
        assertEquals("English", result.indexLanguage)
        assertEquals("Russian", result.contentsLanguage)

        val metadata = repository.selectDictionaries().single()
        assertEquals(result.dictionaryId, metadata.id)
        assertEquals("en-ru", metadata.code)
        assertEquals("Artificial En-Ru", metadata.name)
        assertEquals("en-ru", metadata.direction)
        assertEquals("English", metadata.indexLanguage)
        assertEquals("Russian", metadata.contentsLanguage)
        assertEquals("sample-en-ru.dsl", metadata.sourceFile)
        assertEquals(42, metadata.createdAt)

        val apple = repository.lookupExact("APPLE").single()
        assertEquals(result.dictionaryId, apple.dictionaryId)
        assertEquals("apple", apple.headword)
        assertEquals(" [trn][m1]яблоко[/m][/trn]", apple.articleRaw)
        assertTrue(apple.articleHtml.startsWith("<!doctype html>"))
        assertTrue(apple.articleHtml.contains("<div class=\"trn\">"))
        assertTrue(apple.articleHtml.contains("яблоко"))

        assertEquals(
            listOf("apple", "apricot"),
            repository.suggest("ap", limit = 10).map { it.headword },
        )
    }

    @Test
    fun importsRussianEnglishDslTextAndDetectsDirection() {
        val repository = newRepository()
        val importer = DictionaryImporter(repository)

        val result = importer.importDslText(
            sourceFileName = "sample-ru-en.dsl",
            text = """
                #NAME "Artificial Ru-En"
                #INDEX_LANGUAGE "Russian"
                #CONTENTS_LANGUAGE "English"

                ёж
                 [trn]hedgehog[/trn]
            """.trimIndent(),
        )

        assertEquals("Artificial Ru-En", result.dictionaryName)
        assertEquals("ru-en", result.direction)
        assertEquals(1, result.entryCount)

        val metadata = repository.selectDictionaries().single()
        assertEquals("ru-en", metadata.code)
        assertEquals("ru-en", metadata.direction)
        assertEquals("Russian", metadata.indexLanguage)
        assertEquals("English", metadata.contentsLanguage)

        assertEquals("ёж", repository.lookupExact("ЕЖ").single().headword)
        assertEquals("ёж", repository.suggest("ё", limit = 10).single().headword)
    }

    @Test
    fun progressCallbackReceivesImportEvents() {
        val repository = newRepository()
        val importer = DictionaryImporter(
            repository = repository,
            limits = ImportLimits(insertBatchSize = 1),
        )
        val events = mutableListOf<ImportProgress>()

        importer.importDslText(
            sourceFileName = "progress.dsl",
            text = """
                #NAME "Progress"
                #INDEX_LANGUAGE "English"
                #CONTENTS_LANGUAGE "Russian"

                one
                 один
                two
                 два
            """.trimIndent(),
            progress = events::add,
        )

        assertEquals(ImportProgress.Started, events.first())
        assertTrue(events.any { it is ImportProgress.ParsingDictionary })
        assertEquals(
            listOf(0L, 1L, 2L),
            events.filterIsInstance<ImportProgress.Indexing>().map { it.processedEntries },
        )
        assertEquals(ImportProgress.Completed, events.last())
    }

    @Test
    fun streamingImportProcessesManyEntriesInBatches() {
        val repository = newRepository()
        val importer = DictionaryImporter(
            repository = repository,
            limits = ImportLimits(insertBatchSize = 100),
            currentTimeMillis = { 42 },
        )
        val entryCount = 2_500
        val lines = sequence {
            yield("#NAME \"Many Streaming Entries\"")
            yield("#INDEX_LANGUAGE \"English\"")
            yield("#CONTENTS_LANGUAGE \"Russian\"")
            yield("")
            repeat(entryCount) { index ->
                yield("word$index")
                yield(" [trn]value$index[/trn]")
            }
        }

        val result = importer.importDslLines(
            sourceFileName = "many.dsl",
            lines = lines,
        )

        assertEquals("Many Streaming Entries", result.dictionaryName)
        assertEquals(entryCount.toLong(), result.entryCount)
        assertEquals(0, result.skippedCount)
        assertEquals("word0", repository.lookupExact("WORD0").single().headword)
        assertEquals("word2499", repository.lookupExact("WORD2499").single().headword)
    }

    @Test
    fun skipsOversizedEntries() {
        val repository = newRepository()
        val importer = DictionaryImporter(
            repository = repository,
            limits = ImportLimits(
                maxHeadwordLength = 5,
                maxArticleRawSize = 10,
            ),
        )

        val result = importer.importDslText(
            sourceFileName = "limits.dsl",
            text = """
                #NAME "Limits"
                #INDEX_LANGUAGE "English"
                #CONTENTS_LANGUAGE "Russian"

                ok
                 one
                toolong
                 two
                big
                 abcdefghijklmnopqrstuvwxyz
            """.trimIndent(),
        )

        assertEquals(1, result.entryCount)
        assertEquals(2, result.skippedCount)
        assertEquals(
            listOf(ImportWarningReason.ARTICLE_RAW_TOO_LARGE, ImportWarningReason.HEADWORD_TOO_LONG),
            result.warnings.map { it.reason },
        )
        assertEquals("ok", repository.lookupExact("ok").single().headword)
        assertTrue(repository.lookupExact("toolong").isEmpty())
        assertTrue(repository.lookupExact("big").isEmpty())
    }

    @Test
    fun skipsEntriesWhenConvertedHtmlExceedsLimit() {
        val repository = newRepository()
        val importer = DictionaryImporter(
            repository = repository,
            limits = ImportLimits(maxArticleHtmlSize = 10),
        )

        val result = importer.importDslText(
            sourceFileName = "html-limit.dsl",
            text = """
                #NAME "Html Limit"
                #INDEX_LANGUAGE "English"
                #CONTENTS_LANGUAGE "Russian"

                alpha
                 one
            """.trimIndent(),
        )

        assertEquals(0, result.entryCount)
        assertEquals(1, result.skippedCount)
        assertEquals(ImportWarningReason.ARTICLE_HTML_TOO_LARGE, result.warnings.single().reason)
        assertTrue(repository.lookupExact("alpha").isEmpty())
    }

    @Test
    fun skipsEntriesAfterMaximumEntryLimit() {
        val repository = newRepository()
        val importer = DictionaryImporter(
            repository = repository,
            limits = ImportLimits(maxEntriesPerImport = 1),
        )

        val result = importer.importDslText(
            sourceFileName = "max-entries.dsl",
            text = """
                #NAME "Max Entries"
                #INDEX_LANGUAGE "English"
                #CONTENTS_LANGUAGE "Russian"

                first
                 one
                second
                 two
            """.trimIndent(),
        )

        assertEquals(1, result.entryCount)
        assertEquals(1, result.skippedCount)
        assertEquals("first", repository.lookupExact("first").single().headword)
        assertTrue(repository.lookupExact("second").isEmpty())
    }

    @Test
    fun emptyDslImportReturnsSafeResult() {
        val repository = newRepository()
        val importer = DictionaryImporter(repository)

        val result = importer.importDslText(
            sourceFileName = "empty.dsl",
            text = "",
        )

        assertTrue(result.dictionaryId > 0)
        assertEquals("empty", result.dictionaryName)
        assertEquals("unknown", result.direction)
        assertEquals(0, result.entryCount)
        assertEquals(0, result.skippedCount)
        assertEquals("empty.dsl", result.sourceFileName)
        assertEquals(1, repository.selectDictionaries().size)
    }

    @Test
    fun failedImportEmitsFailureEvent() {
        val importer = DictionaryImporter(FailingRepository())
        val events = mutableListOf<ImportProgress>()

        val failure = runCatching {
            importer.importDslText(
                sourceFileName = "failure.dsl",
                text = """
                    #NAME "Failure"

                    word
                     article
                """.trimIndent(),
                progress = events::add,
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertIs<ImportProgress.Failed>(events.last())
    }

    private fun newRepository(): DictionaryRepository {
        val driver = JvmTestDriverFactory().createDriver()
        return SqlDelightDictionaryRepository(PocketDslDatabase(driver))
    }

    private class FailingRepository : DictionaryRepository {
        override fun insertDictionaryMetadata(metadata: NewDictionaryMetadata): Long =
            error("metadata insert failed")

        override fun insertEntries(dictionaryId: Long, entries: List<NewDictionaryEntry>): List<Long> =
            emptyList()

        override fun selectDictionaries(): List<DictionaryMetadata> =
            emptyList()

        override fun selectEntryById(id: Long): DictionaryEntry? =
            null

        override fun lookupExact(word: String): List<DictionaryEntry> =
            emptyList()

        override fun suggest(prefix: String, limit: Long): List<DictionarySuggestion> =
            emptyList()

        override fun addFavorite(entryId: Long, createdAt: Long) = Unit

        override fun removeFavorite(entryId: Long) = Unit

        override fun listFavorites(): List<DictionaryEntry> =
            emptyList()

        override fun addHistoryItem(query: String, entryId: Long?, createdAt: Long): Long =
            0

        override fun listHistory(limit: Long): List<HistoryItem> =
            emptyList()
    }
}
