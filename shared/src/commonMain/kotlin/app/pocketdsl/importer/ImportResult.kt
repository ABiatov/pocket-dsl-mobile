package app.pocketdsl.importer

data class ImportResult(
    val dictionaryId: Long,
    val dictionaryName: String,
    val direction: String,
    val entryCount: Long,
    val skippedCount: Long,
    val sourceFileName: String,
    val indexLanguage: String?,
    val contentsLanguage: String?,
    val warnings: List<ImportWarning> = emptyList(),
)

data class ImportWarning(
    val reason: ImportWarningReason,
    val count: Long,
    val limit: Int? = null,
)

enum class ImportWarningReason {
    HEADWORD_TOO_LONG,
    ARTICLE_RAW_TOO_LARGE,
    ARTICLE_HTML_TOO_LARGE,
    MAX_ENTRIES_REACHED,
}
