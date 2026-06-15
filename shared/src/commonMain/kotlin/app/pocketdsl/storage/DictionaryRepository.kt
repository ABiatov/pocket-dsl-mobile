package app.pocketdsl.storage

import app.pocketdsl.db.Dictionaries
import app.pocketdsl.db.Entries
import app.pocketdsl.db.History
import app.pocketdsl.db.PocketDslDatabase
import app.pocketdsl.db.Suggest
import app.pocketdsl.search.WordNormalizer

data class DictionaryMetadata(
    val id: Long,
    val code: String,
    val name: String,
    val direction: String,
    val indexLanguage: String?,
    val contentsLanguage: String?,
    val sourceFile: String,
    val createdAt: Long,
)

data class NewDictionaryMetadata(
    val code: String,
    val name: String,
    val direction: String,
    val indexLanguage: String?,
    val contentsLanguage: String?,
    val sourceFile: String,
    val createdAt: Long,
)

data class DictionaryEntry(
    val id: Long,
    val dictionaryId: Long,
    val headword: String,
    val normalizedHeadword: String,
    val articleRaw: String,
    val articleHtml: String,
)

data class NewDictionaryEntry(
    val headword: String,
    val articleRaw: String,
    val articleHtml: String,
)

data class DictionarySuggestion(
    val entryId: Long,
    val dictionaryId: Long,
    val headword: String,
)

data class HistoryItem(
    val id: Long,
    val query: String,
    val entryId: Long?,
    val createdAt: Long,
)

interface DictionaryRepository {
    fun insertDictionaryMetadata(metadata: NewDictionaryMetadata): Long

    fun insertEntries(dictionaryId: Long, entries: List<NewDictionaryEntry>): List<Long>

    fun selectDictionaries(): List<DictionaryMetadata>

    fun selectEntryById(id: Long): DictionaryEntry?

    fun lookupExact(word: String): List<DictionaryEntry>

    fun suggest(prefix: String, limit: Long): List<DictionarySuggestion>

    fun addFavorite(entryId: Long, createdAt: Long)

    fun removeFavorite(entryId: Long)

    fun listFavorites(): List<DictionaryEntry>

    fun addHistoryItem(query: String, entryId: Long?, createdAt: Long): Long

    fun listHistory(limit: Long): List<HistoryItem>
}

class SqlDelightDictionaryRepository(
    private val database: PocketDslDatabase,
) : DictionaryRepository {
    private val queries = database.dictionaryQueries

    override fun insertDictionaryMetadata(metadata: NewDictionaryMetadata): Long {
        queries.insertDictionary(
            code = metadata.code,
            name = metadata.name,
            direction = metadata.direction,
            indexLanguage = metadata.indexLanguage,
            contentsLanguage = metadata.contentsLanguage,
            sourceFile = metadata.sourceFile,
            createdAt = metadata.createdAt,
        )
        return lastInsertRowId()
    }

    override fun insertEntries(dictionaryId: Long, entries: List<NewDictionaryEntry>): List<Long> {
        if (entries.isEmpty()) return emptyList()

        val insertedIds = mutableListOf<Long>()
        database.transaction {
            entries.forEach { entry ->
                queries.insertEntry(
                    dictionaryId = dictionaryId,
                    headword = entry.headword,
                    normalizedHeadword = WordNormalizer.normalize(entry.headword),
                    articleRaw = entry.articleRaw,
                    articleHtml = entry.articleHtml,
                )
                insertedIds += lastInsertRowId()
            }
        }
        return insertedIds
    }

    override fun selectDictionaries(): List<DictionaryMetadata> =
        queries.selectDictionaries().executeAsList().map(::mapDictionary)

    override fun selectEntryById(id: Long): DictionaryEntry? =
        queries.selectEntryById(id).executeAsOneOrNull()?.let(::mapEntry)

    override fun lookupExact(word: String): List<DictionaryEntry> =
        queries.selectByExactWord(WordNormalizer.normalize(word)).executeAsList().map(::mapEntry)

    override fun suggest(prefix: String, limit: Long): List<DictionarySuggestion> =
        queries.suggest(
            normalizedHeadword = "${WordNormalizer.normalize(prefix)}%",
            value_ = limit,
        ).executeAsList().map(::mapSuggestion)

    override fun addFavorite(entryId: Long, createdAt: Long) {
        queries.insertFavorite(entryId = entryId, createdAt = createdAt)
    }

    override fun removeFavorite(entryId: Long) {
        queries.deleteFavorite(entryId)
    }

    override fun listFavorites(): List<DictionaryEntry> =
        queries.selectFavorites().executeAsList().map(::mapEntry)

    override fun addHistoryItem(query: String, entryId: Long?, createdAt: Long): Long {
        queries.insertHistory(query = query, entryId = entryId, createdAt = createdAt)
        return lastInsertRowId()
    }

    override fun listHistory(limit: Long): List<HistoryItem> =
        queries.selectHistory(limit).executeAsList().map(::mapHistory)

    private fun lastInsertRowId(): Long =
        queries.lastInsertRowId().executeAsOne()

    private fun mapDictionary(dictionary: Dictionaries): DictionaryMetadata =
        DictionaryMetadata(
            id = dictionary.id,
            code = dictionary.code,
            name = dictionary.name,
            direction = dictionary.direction,
            indexLanguage = dictionary.indexLanguage,
            contentsLanguage = dictionary.contentsLanguage,
            sourceFile = dictionary.sourceFile,
            createdAt = dictionary.createdAt,
        )

    private fun mapEntry(entry: Entries): DictionaryEntry =
        DictionaryEntry(
            id = entry.id,
            dictionaryId = entry.dictionaryId,
            headword = entry.headword,
            normalizedHeadword = entry.normalizedHeadword,
            articleRaw = entry.articleRaw,
            articleHtml = entry.articleHtml,
        )

    private fun mapSuggestion(suggestion: Suggest): DictionarySuggestion =
        DictionarySuggestion(
            entryId = suggestion.id,
            dictionaryId = suggestion.dictionaryId,
            headword = suggestion.headword,
        )

    private fun mapHistory(history: History): HistoryItem =
        HistoryItem(
            id = history.id,
            query = history.query,
            entryId = history.entryId,
            createdAt = history.createdAt,
        )
}
