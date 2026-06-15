package app.pocketdsl.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchListKeyTest {
    @Test
    fun suggestionKeysRemainUniqueWhenEntryIdsRepeat() {
        val suggestions = listOf(
            UiSuggestion(
                entryId = 9870,
                dictionaryId = 1,
                dictionaryName = "Ru-En",
                headword = "дом",
                dictionaryLabel = "Ru-En",
            ),
            UiSuggestion(
                entryId = 9870,
                dictionaryId = 1,
                dictionaryName = "Ru-En",
                headword = "дом",
                dictionaryLabel = "Ru-En",
            ),
        )

        val keys = suggestions.mapIndexed(::suggestionLazyKey)

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun resultAndSuggestionKeysDoNotCollideForSameEntry() {
        val entryId = 9870L
        val resultKey = searchResultLazyKey(
            index = 0,
            result = UiSearchResult(
                entryId = entryId,
                dictionaryId = 1,
                dictionaryName = "Ru-En",
                headword = "дом",
                dictionaryLabel = "Ru-En",
            ),
        )
        val suggestionKey = suggestionLazyKey(
            index = 0,
            suggestion = UiSuggestion(
                entryId = entryId,
                dictionaryId = 1,
                dictionaryName = "Ru-En",
                headword = "дом",
                dictionaryLabel = "Ru-En",
            ),
        )

        assertEquals(2, setOf(resultKey, suggestionKey).size)
    }
}
