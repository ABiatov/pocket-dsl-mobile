package app.pocketdsl.importer

import app.pocketdsl.dsl.DslParser
import app.pocketdsl.dsl.DslToHtml
import app.pocketdsl.dsl.DslDictionaryMeta
import app.pocketdsl.dsl.DslEntry
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

            val dictionaryId = try {
                repository.insertDictionaryMetadata(
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
            } catch (cause: Throwable) {
                throw DictionaryImportException(
                    stage = DictionaryImportStage.DATABASE_INSERT,
                    message = "Database insert failure while saving dictionary metadata",
                    cause = cause,
                )
            }

            var insertedCount = 0L
            var skippedCount = 0L
            val batch = mutableListOf<NewDictionaryEntry>()
            val warningCounts = mutableMapOf<ImportWarningReason, Long>()

            fun flushBatch() {
                if (batch.isEmpty()) return
                try {
                    repository.insertEntries(dictionaryId = dictionaryId, entries = batch)
                } catch (cause: Throwable) {
                    throw DictionaryImportException(
                        stage = DictionaryImportStage.DATABASE_INSERT,
                        message = "Database insert failure after importing $insertedCount entries",
                        cause = cause,
                    )
                }
                insertedCount += batch.size
                batch.clear()
                emit(ImportProgress.Indexing(processedEntries = insertedCount))
            }

            fun skip(reason: ImportWarningReason) {
                skippedCount += 1
                warningCounts[reason] = (warningCounts[reason] ?: 0L) + 1L
            }

            emit(ImportProgress.Indexing(processedEntries = 0))

            parsedEntries.forEach { entry ->
                if (entry.headword.length > limits.maxHeadwordLength) {
                    skip(ImportWarningReason.HEADWORD_TOO_LONG)
                    return@forEach
                }

                if (entry.articleRaw.length > limits.maxArticleRawSize) {
                    skip(ImportWarningReason.ARTICLE_RAW_TOO_LARGE)
                    return@forEach
                }

                if (insertedCount + batch.size >= limits.maxEntriesPerImport.toLong()) {
                    skip(ImportWarningReason.MAX_ENTRIES_REACHED)
                    return@forEach
                }

                val articleHtml = DslToHtml.convert(entry.articleRaw)
                if (articleHtml.length > limits.maxArticleHtmlSize) {
                    skip(ImportWarningReason.ARTICLE_HTML_TOO_LARGE)
                    return@forEach
                }

                batch += NewDictionaryEntry(
                    headword = entry.headword,
                    articleRaw = entry.articleRaw,
                    articleHtml = articleHtml,
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
                warnings = warningCounts.toImportWarnings(limits),
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

    fun importDslLines(
        sourceFileName: String,
        lines: Sequence<String>,
        progress: ((ImportProgress) -> Unit)? = null,
    ): ImportResult {
        fun emit(event: ImportProgress) {
            progress?.invoke(event)
        }

        try {
            emit(ImportProgress.Started)
            emit(ImportProgress.ParsingDictionary(dictionaryName = null))

            var dictionaryId: Long? = null
            var dictionaryName: String? = null
            var direction: String? = null
            var insertedMetadata: DslDictionaryMeta? = null
            var insertedCount = 0L
            var skippedCount = 0L
            var emittedInitialIndexing = false
            val batch = mutableListOf<NewDictionaryEntry>()
            val warningCounts = mutableMapOf<ImportWarningReason, Long>()

            fun ensureDictionary(metadata: DslDictionaryMeta): Long {
                dictionaryId?.let { return it }

                val resolvedDictionaryName = metadata.name?.takeIf { it.isNotBlank() }
                    ?: sourceFileName.toDictionaryName()
                val resolvedDirection = detectDirection(
                    indexLanguage = metadata.indexLanguage,
                    contentsLanguage = metadata.contentsLanguage,
                )

                emit(ImportProgress.ParsingDictionary(dictionaryName = resolvedDictionaryName))

                val id = try {
                    repository.insertDictionaryMetadata(
                        NewDictionaryMetadata(
                            code = resolvedDirection,
                            name = resolvedDictionaryName,
                            direction = resolvedDirection,
                            indexLanguage = metadata.indexLanguage,
                            contentsLanguage = metadata.contentsLanguage,
                            sourceFile = sourceFileName,
                            createdAt = currentTimeMillis(),
                        ),
                    )
                } catch (cause: Throwable) {
                    throw DictionaryImportException(
                        stage = DictionaryImportStage.DATABASE_INSERT,
                        message = "Database insert failure while saving dictionary metadata",
                        cause = cause,
                    )
                }

                dictionaryId = id
                dictionaryName = resolvedDictionaryName
                direction = resolvedDirection
                insertedMetadata = metadata
                if (!emittedInitialIndexing) {
                    emit(ImportProgress.Indexing(processedEntries = 0))
                    emittedInitialIndexing = true
                }
                return id
            }

            fun flushBatch() {
                if (batch.isEmpty()) return
                val id = dictionaryId ?: error("Dictionary metadata was not created before entry insert")
                try {
                    repository.insertEntries(dictionaryId = id, entries = batch)
                } catch (cause: Throwable) {
                    throw DictionaryImportException(
                        stage = DictionaryImportStage.DATABASE_INSERT,
                        message = "Database insert failure after importing $insertedCount entries",
                        cause = cause,
                    )
                }
                insertedCount += batch.size
                batch.clear()
                emit(ImportProgress.Indexing(processedEntries = insertedCount))
            }

            fun skip(reason: ImportWarningReason) {
                skippedCount += 1
                warningCounts[reason] = (warningCounts[reason] ?: 0L) + 1L
            }

            fun importEntry(entry: DslEntry) {
                if (entry.headword.length > limits.maxHeadwordLength) {
                    skip(ImportWarningReason.HEADWORD_TOO_LONG)
                    return
                }

                if (entry.articleRawExceededLimit || entry.articleRaw.length > limits.maxArticleRawSize) {
                    skip(ImportWarningReason.ARTICLE_RAW_TOO_LARGE)
                    return
                }

                if (insertedCount + batch.size >= limits.maxEntriesPerImport.toLong()) {
                    skip(ImportWarningReason.MAX_ENTRIES_REACHED)
                    return
                }

                val articleHtml = DslToHtml.convert(entry.articleRaw)
                if (articleHtml.length > limits.maxArticleHtmlSize) {
                    skip(ImportWarningReason.ARTICLE_HTML_TOO_LARGE)
                    return
                }

                batch += NewDictionaryEntry(
                    headword = entry.headword,
                    articleRaw = entry.articleRaw,
                    articleHtml = articleHtml,
                )

                if (batch.size >= limits.insertBatchSize) {
                    flushBatch()
                }
            }

            val finalMetadata = parser.parseStreaming(
                lines = lines,
                maxArticleRawChars = limits.maxArticleRawSize,
                onMetadata = { metadata -> ensureDictionary(metadata) },
                onEntry = ::importEntry,
            )

            val id = ensureDictionary(finalMetadata)
            flushBatch()
            emit(ImportProgress.Completed)

            val metadata = insertedMetadata ?: finalMetadata
            return ImportResult(
                dictionaryId = id,
                dictionaryName = dictionaryName ?: sourceFileName.toDictionaryName(),
                direction = direction ?: "unknown",
                entryCount = insertedCount,
                skippedCount = skippedCount,
                sourceFileName = sourceFileName,
                indexLanguage = metadata.indexLanguage,
                contentsLanguage = metadata.contentsLanguage,
                warnings = warningCounts.toImportWarnings(limits),
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

    private fun Map<ImportWarningReason, Long>.toImportWarnings(limits: ImportLimits): List<ImportWarning> =
        entries
            .sortedBy { it.key.name }
            .map { (reason, count) ->
                ImportWarning(
                    reason = reason,
                    count = count,
                    limit = when (reason) {
                        ImportWarningReason.HEADWORD_TOO_LONG -> limits.maxHeadwordLength
                        ImportWarningReason.ARTICLE_RAW_TOO_LARGE -> limits.maxArticleRawSize
                        ImportWarningReason.ARTICLE_HTML_TOO_LARGE -> limits.maxArticleHtmlSize
                        ImportWarningReason.MAX_ENTRIES_REACHED -> limits.maxEntriesPerImport
                    },
                )
            }
}

data class ImportLimits(
    val maxHeadwordLength: Int = 512,
    val maxArticleRawSize: Int = 2 * 1024 * 1024,
    val maxArticleHtmlSize: Int = 4 * 1024 * 1024,
    val maxEntriesPerImport: Int = 2_000_000,
    val insertBatchSize: Int = 500,
) {
    init {
        require(maxHeadwordLength > 0) { "maxHeadwordLength must be positive" }
        require(maxArticleRawSize > 0) { "maxArticleRawSize must be positive" }
        require(maxArticleHtmlSize > 0) { "maxArticleHtmlSize must be positive" }
        require(maxEntriesPerImport >= 0) { "maxEntriesPerImport must not be negative" }
        require(insertBatchSize > 0) { "insertBatchSize must be positive" }
    }
}

class DictionaryImportException(
    val stage: DictionaryImportStage,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

enum class DictionaryImportStage {
    DATABASE_INSERT,
}
