package app.pocketdsl.importer

sealed interface ImportProgress {
    data object Started : ImportProgress
    data class ExtractingArchive(val currentFile: String?) : ImportProgress
    data class ParsingDictionary(val dictionaryName: String?) : ImportProgress
    data class Indexing(val processedEntries: Long) : ImportProgress
    data object Completed : ImportProgress
    data class Failed(val message: String, val cause: Throwable? = null) : ImportProgress
}
