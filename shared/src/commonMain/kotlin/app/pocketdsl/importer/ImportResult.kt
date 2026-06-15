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
)
