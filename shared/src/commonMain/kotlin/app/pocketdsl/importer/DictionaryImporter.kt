package app.pocketdsl.importer

import app.pocketdsl.dsl.DslParser
import app.pocketdsl.dsl.DslToHtml
import app.pocketdsl.storage.DictionaryRepository
import app.pocketdsl.storage.NewDictionaryEntry
import app.pocketdsl.storage.NewDictionaryMetadata

class DictionaryImporter(
    private val repository: DictionaryRepository,
    private val parser: DslParser = DslParser(),
    private val limits: ImportLimits = ImportLimits(),
    private val currentTimeMillis: () -> Long = { 0L },
) {
    fun importDslText(
        sourceFileName: String,
        text: String,
        progress: ((ImportProgress) -> Unit)? = null,
    ): ImportResult {
        fun emit(event: ImportProgress) {
            progress?.invoke(event)
        }

        try {
            emit(ImportProgress.Started)
            emit(ImportProgress.ParsingDictionary(dictionaryName = null))

            val (metadata, parsedEntries) = parser.parse(text)
            val dictionaryName = metadata.name?.takeIf { it.isNotBlank() }
                ?: sourceFileName.toDictionaryName()
            val direction = detectDirection(
                indexLanguage = metadata.indexLanguage,
                contentsLanguage = metadata.contentsLanguage,
            )

            emit(ImportProgress.ParsingDictionary(dictionaryName = dictionaryName))

            val dictionaryId = repository.insertDictionaryMetadata(
                NewDictionaryMetadata(
                    code = direction,
                    name = dictionaryName,
                    direction = direction,
                    indexLanguage = metadata.indexLanguage,
                    contentsLanguage = metadata.contentsLanguage,
                    sourceFile = sourceFileName,
                    createdAt = currentTimeMillis(),
                ),
            )

            var insertedCount = 0L
            var skippedCount = 0L
            val batch = mutableListOf<NewDictionaryEntry>()

            fun flushBatch() {
                if (batch.isEmpty()) return
                repository.insertEntries(dictionaryId = dictionaryId, entries = batch)
                insertedCount += batch.size
                batch.clear()
                emit(ImportProgress.Indexing(processedEntries = insertedCount))
            }

            emit(ImportProgress.Indexing(processedEntries = 0))

            parsedEntries.forEach { entry ->
                if (entry.headword.length > limits.maxHeadwordLength ||
                    entry.articleRaw.length > limits.maxArticleRawSize ||
                    insertedCount + batch.size >= limits.maxEntriesPerImport.toLong()
                ) {
                    skippedCount += 1
                    return@forEach
                }

                batch += NewDictionaryEntry(
                    headword = entry.headword,
                    articleRaw = entry.articleRaw,
                    articleHtml = DslToHtml.convert(entry.articleRaw),
                )

                if (batch.size >= limits.insertBatchSize) {
                    flushBatch()
                }
            }

            flushBatch()
            emit(ImportProgress.Completed)

            return ImportResult(
                dictionaryId = dictionaryId,
                dictionaryName = dictionaryName,
                direction = direction,
                entryCount = insertedCount,
                skippedCount = skippedCount,
                sourceFileName = sourceFileName,
                indexLanguage = metadata.indexLanguage,
                contentsLanguage = metadata.contentsLanguage,
            )
        } catch (cause: Throwable) {
            emit(
                ImportProgress.Failed(
                    message = cause.message ?: "Dictionary import failed",
                    cause = cause,
                ),
            )
            throw cause
        }
    }

    private fun detectDirection(indexLanguage: String?, contentsLanguage: String?): String =
        when (indexLanguage.normalizedLanguageName() to contentsLanguage.normalizedLanguageName()) {
            "english" to "russian" -> "en-ru"
            "russian" to "english" -> "ru-en"
            else -> "unknown"
        }

    private fun String?.normalizedLanguageName(): String? =
        this?.trim()?.lowercase()

    private fun String.toDictionaryName(): String {
        val fileName = substringAfterLast('/').substringAfterLast('\\')
        return fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
            .takeIf { it.isNotBlank() }
            ?: "Imported Dictionary"
    }
}

data class ImportLimits(
    val maxHeadwordLength: Int = 512,
    val maxArticleRawSize: Int = 2 * 1024 * 1024,
    val maxEntriesPerImport: Int = 2_000_000,
    val insertBatchSize: Int = 500,
) {
    init {
        require(maxHeadwordLength > 0) { "maxHeadwordLength must be positive" }
        require(maxArticleRawSize > 0) { "maxArticleRawSize must be positive" }
        require(maxEntriesPerImport >= 0) { "maxEntriesPerImport must not be negative" }
        require(insertBatchSize > 0) { "insertBatchSize must be positive" }
    }
}
