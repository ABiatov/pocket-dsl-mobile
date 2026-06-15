package app.pocketdsl.search

interface DictionarySearch {
    suspend fun searchExact(query: String): List<SearchResult>
    suspend fun suggest(prefix: String, limit: Int): List<SearchSuggestion>
}
