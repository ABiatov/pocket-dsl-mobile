package app.pocketdsl.search

data class SearchSuggestion(
    val entryId: Long,
    val dictionaryId: Long,
    val headword: String,
)
