package app.pocketdsl.storage

import app.pocketdsl.db.PocketDslDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SqlDelightDictionaryRepositoryTest {
    @Test
    fun insertsDictionaryMetadata() {
        val repository = newRepository()

        val dictionaryId = repository.insertDictionaryMetadata(sampleMetadata())

        assertEquals(
            listOf(sampleMetadataResult(id = dictionaryId)),
            repository.selectDictionaries(),
        )
    }

    @Test
    fun insertsEntriesAndSelectsById() {
        val repository = newRepository()
        val dictionaryId = repository.insertDictionaryMetadata(sampleMetadata())

        val entryIds = repository.insertEntries(
            dictionaryId = dictionaryId,
            entries = listOf(
                NewDictionaryEntry(
                    headword = "Apple",
                    articleRaw = " [trn]яблоко[/trn]",
                    articleHtml = "<html>apple</html>",
                ),
                NewDictionaryEntry(
                    headword = "Apricot",
                    articleRaw = " [trn]абрикос[/trn]",
                    articleHtml = "<html>apricot</html>",
                ),
            ),
        )

        assertEquals(2, entryIds.size)
        assertEquals("Apple", repository.selectEntryById(entryIds.first())?.headword)
        assertEquals("Apricot", repository.selectEntryById(entryIds.last())?.headword)
        assertNull(repository.selectEntryById(999))
    }

    @Test
    fun exactLookupNormalizesSearchTerm() {
        val repository = newRepository()
        val dictionaryId = repository.insertDictionaryMetadata(sampleMetadata())
        val entryId = repository.insertEntries(
            dictionaryId = dictionaryId,
            entries = listOf(
                NewDictionaryEntry(
                    headword = "Apple",
                    articleRaw = " [trn]яблоко[/trn]",
                    articleHtml = "<html>apple</html>",
                ),
            ),
        ).single()

        assertEquals(
            listOf(
                DictionaryEntry(
                    id = entryId,
                    dictionaryId = dictionaryId,
                    headword = "Apple",
                    normalizedHeadword = "apple",
                    articleRaw = " [trn]яблоко[/trn]",
                    articleHtml = "<html>apple</html>",
                ),
            ),
            repository.lookupExact("  APPLE  "),
        )
    }

    @Test
    fun prefixSuggestionsNormalizePrefix() {
        val repository = newRepository()
        val dictionaryId = repository.insertDictionaryMetadata(sampleMetadata())
        val entryIds = repository.insertEntries(
            dictionaryId = dictionaryId,
            entries = listOf(
                NewDictionaryEntry("Alpha", " [trn]one[/trn]", "<html>alpha</html>"),
                NewDictionaryEntry("Alpine", " [trn]two[/trn]", "<html>alpine</html>"),
                NewDictionaryEntry("Beta", " [trn]three[/trn]", "<html>beta</html>"),
            ),
        )

        assertEquals(
            listOf(
                DictionarySuggestion(entryIds[0], dictionaryId, "Alpha"),
                DictionarySuggestion(entryIds[1], dictionaryId, "Alpine"),
            ),
            repository.suggest(" AL", limit = 10),
        )
    }

    @Test
    fun suggestionEntryIdLoadsTheSuggestedEntry() {
        val repository = newRepository()
        val dictionaryId = repository.insertDictionaryMetadata(sampleMetadata())
        repository.insertEntries(
            dictionaryId = dictionaryId,
            entries = listOf(
                NewDictionaryEntry("Alpha", " [trn]one[/trn]", "<html>alpha</html>"),
                NewDictionaryEntry("Alpine", " [trn]two[/trn]", "<html>alpine</html>"),
            ),
        )

        val suggestion = repository.suggest("alp", limit = 10).first()
        val entry = assertNotNull(repository.selectEntryById(suggestion.entryId))

        assertEquals(suggestion.entryId, entry.id)
        assertEquals(suggestion.dictionaryId, entry.dictionaryId)
        assertEquals(suggestion.headword, entry.headword)
        assertEquals("<html>alpha</html>", entry.articleHtml)
    }

    @Test
    fun prefixSuggestionsEscapeSqlLikeWildcards() {
        val repository = newRepository()
        val dictionaryId = repository.insertDictionaryMetadata(sampleMetadata())
        val entryIds = repository.insertEntries(
            dictionaryId = dictionaryId,
            entries = listOf(
                NewDictionaryEntry("a_1", " [trn]literal underscore[/trn]", "<html>a_1</html>"),
                NewDictionaryEntry("ab1", " [trn]plain letters[/trn]", "<html>ab1</html>"),
                NewDictionaryEntry("a%2", " [trn]literal percent[/trn]", "<html>a%2</html>"),
                NewDictionaryEntry("ax2", " [trn]plain wildcard match[/trn]", "<html>ax2</html>"),
            ),
        )

        assertEquals(
            listOf(DictionarySuggestion(entryIds[0], dictionaryId, "a_1")),
            repository.suggest("a_", limit = 10),
        )
        assertEquals(
            listOf(DictionarySuggestion(entryIds[2], dictionaryId, "a%2")),
            repository.suggest("a%", limit = 10),
        )
    }

    @Test
    fun storesRawDslAndHtml() {
        val repository = newRepository()
        val dictionaryId = repository.insertDictionaryMetadata(sampleMetadata())
        val entryId = repository.insertEntries(
            dictionaryId = dictionaryId,
            entries = listOf(
                NewDictionaryEntry(
                    headword = "raw",
                    articleRaw = " [b]raw DSL[/b]",
                    articleHtml = "<!doctype html><strong>raw DSL</strong>",
                ),
            ),
        ).single()

        val entry = assertNotNull(repository.selectEntryById(entryId))
        assertEquals(" [b]raw DSL[/b]", entry.articleRaw)
        assertEquals("<!doctype html><strong>raw DSL</strong>", entry.articleHtml)
    }

    @Test
    fun addsRemovesAndListsFavorites() {
        val repository = newRepository()
        val dictionaryId = repository.insertDictionaryMetadata(sampleMetadata())
        val entryIds = repository.insertEntries(
            dictionaryId = dictionaryId,
            entries = listOf(
                NewDictionaryEntry("first", " [trn]one[/trn]", "<html>first</html>"),
                NewDictionaryEntry("second", " [trn]two[/trn]", "<html>second</html>"),
            ),
        )

        repository.addFavorite(entryIds[0], createdAt = 100)
        repository.addFavorite(entryIds[1], createdAt = 200)

        assertEquals(
            listOf("second", "first"),
            repository.listFavorites().map { it.headword },
        )

        repository.removeFavorite(entryIds[1])

        assertEquals(
            listOf("first"),
            repository.listFavorites().map { it.headword },
        )
    }

    @Test
    fun addsAndListsHistory() {
        val repository = newRepository()
        val dictionaryId = repository.insertDictionaryMetadata(sampleMetadata())
        val entryId = repository.insertEntries(
            dictionaryId = dictionaryId,
            entries = listOf(NewDictionaryEntry("history", " [trn]item[/trn]", "<html>history</html>")),
        ).single()

        val olderId = repository.addHistoryItem(query = "old", entryId = null, createdAt = 100)
        val newerId = repository.addHistoryItem(query = "new", entryId = entryId, createdAt = 200)

        assertEquals(
            listOf(
                HistoryItem(id = newerId, query = "new", entryId = entryId, createdAt = 200),
                HistoryItem(id = olderId, query = "old", entryId = null, createdAt = 100),
            ),
            repository.listHistory(limit = 10),
        )
        assertEquals(1, repository.listHistory(limit = 1).size)
    }

    @Test
    fun normalizesRussianYoDuringInsertAndSearch() {
        val repository = newRepository()
        val dictionaryId = repository.insertDictionaryMetadata(
            sampleMetadata(
                code = "ru-en",
                name = "Sample Ru-En",
                direction = "ru-en",
                indexLanguage = "Russian",
                contentsLanguage = "English",
            ),
        )
        val entryId = repository.insertEntries(
            dictionaryId = dictionaryId,
            entries = listOf(
                NewDictionaryEntry(
                    headword = "ёж",
                    articleRaw = " [trn]hedgehog[/trn]",
                    articleHtml = "<html>hedgehog</html>",
                ),
            ),
        ).single()

        assertEquals(
            listOf(
                DictionaryEntry(
                    id = entryId,
                    dictionaryId = dictionaryId,
                    headword = "ёж",
                    normalizedHeadword = "еж",
                    articleRaw = " [trn]hedgehog[/trn]",
                    articleHtml = "<html>hedgehog</html>",
                ),
            ),
            repository.lookupExact("ЕЖ"),
        )
        assertEquals(
            listOf(DictionarySuggestion(entryId, dictionaryId, "ёж")),
            repository.suggest("Ё", limit = 10),
        )
    }

    private fun newRepository(): DictionaryRepository {
        val driver = JvmTestDriverFactory().createDriver()
        return SqlDelightDictionaryRepository(PocketDslDatabase(driver))
    }

    private fun sampleMetadata(
        code: String = "en-ru",
        name: String = "Sample En-Ru",
        direction: String = "en-ru",
        indexLanguage: String? = "English",
        contentsLanguage: String? = "Russian",
    ): NewDictionaryMetadata =
        NewDictionaryMetadata(
            code = code,
            name = name,
            direction = direction,
            indexLanguage = indexLanguage,
            contentsLanguage = contentsLanguage,
            sourceFile = "sample.dsl",
            createdAt = 42,
        )

    private fun sampleMetadataResult(id: Long): DictionaryMetadata =
        DictionaryMetadata(
            id = id,
            code = "en-ru",
            name = "Sample En-Ru",
            direction = "en-ru",
            indexLanguage = "English",
            contentsLanguage = "Russian",
            sourceFile = "sample.dsl",
            createdAt = 42,
        )
}
