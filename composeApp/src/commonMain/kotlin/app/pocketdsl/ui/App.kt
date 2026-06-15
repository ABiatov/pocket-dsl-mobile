package app.pocketdsl.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
fun App() {
    MaterialTheme {
        Surface {
            Text("PocketDSL Mobile")
        }
    }
}

@Composable
fun App(
    state: PocketDslUiState,
    onImportClick: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onSuggestionClick: (UiSuggestion) -> Unit,
    onResultClick: (UiSearchResult) -> Unit,
    onToggleFavorite: () -> Unit,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "PocketDSL",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Dictionary files are not included. Import files you are allowed to use.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = onImportClick,
                        enabled = !state.isImporting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(min = 180.dp),
                    ) {
                        Text("Import dictionary")
                    }
                }

                if (state.importMessage != null || state.isImporting) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.isImporting) {
                            CircularProgressIndicator()
                        }
                        Text(
                            text = state.importMessage ?: "Importing...",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
                    supportingText = {
                        Text("${state.dictionaryCount} dictionaries imported")
                    },
                )

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    if (maxWidth < 700.dp) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            SearchColumn(
                                state = state,
                                onSuggestionClick = onSuggestionClick,
                                onResultClick = onResultClick,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(if (state.selectedEntry == null) 1f else 0.45f),
                            )
                            if (state.selectedEntry != null) {
                                ArticleColumn(
                                    entry = state.selectedEntry,
                                    onToggleFavorite = onToggleFavorite,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(0.55f),
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            SearchColumn(
                                state = state,
                                onSuggestionClick = onSuggestionClick,
                                onResultClick = onResultClick,
                                modifier = Modifier
                                    .weight(0.42f)
                                    .fillMaxHeight(),
                            )
                            ArticleColumn(
                                entry = state.selectedEntry,
                                onToggleFavorite = onToggleFavorite,
                                modifier = Modifier
                                    .weight(0.58f)
                                    .fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchColumn(
    state: PocketDslUiState,
    onSuggestionClick: (UiSuggestion) -> Unit,
    onResultClick: (UiSearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.searchResults.isNotEmpty()) {
            item {
                Text(
                    text = "Results",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            itemsIndexed(
                state.searchResults,
                key = ::searchResultLazyKey,
            ) { _, result ->
                SearchRow(
                    headword = result.headword,
                    subtitle = result.dictionaryLabel,
                    onClick = { onResultClick(result) },
                )
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
        }

        item {
            Text(
                text = "Suggestions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (state.suggestions.isEmpty()) {
            item {
                Text(
                    text = if (state.query.isBlank()) {
                        "Type a word to search imported dictionaries."
                    } else {
                        "No suggestions."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            itemsIndexed(
                state.suggestions,
                key = ::suggestionLazyKey,
            ) { _, suggestion ->
                SearchRow(
                    headword = suggestion.headword,
                    subtitle = suggestion.dictionaryLabel,
                    onClick = { onSuggestionClick(suggestion) },
                )
            }
        }
    }
}

internal fun searchResultLazyKey(index: Int, result: UiSearchResult): String =
    "result|${result.entryId}|${result.dictionaryId}|${result.headword}|${result.dictionaryLabel}|$index"

internal fun suggestionLazyKey(index: Int, suggestion: UiSuggestion): String =
    "suggestion|${suggestion.entryId}|${suggestion.dictionaryId}|${suggestion.headword}|" +
        "${suggestion.dictionaryLabel}|$index"

@Composable
private fun SearchRow(
    headword: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = headword,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ArticleColumn(
    entry: UiArticleEntry?,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (entry == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("Select a search result to view an article.")
            }
            return
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.headword,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = entry.dictionaryLabel,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onToggleFavorite) {
                Text(if (entry.isFavorite) "Remove favorite" else "Add favorite")
            }
        }

        Spacer(Modifier.height(8.dp))
        ArticleHtmlView(
            html = entry.articleHtml,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

data class PocketDslUiState(
    val query: String = "",
    val dictionaryCount: Int = 0,
    val isImporting: Boolean = false,
    val importMessage: String? = null,
    val suggestions: List<UiSuggestion> = emptyList(),
    val searchResults: List<UiSearchResult> = emptyList(),
    val selectedEntry: UiArticleEntry? = null,
)

data class UiSuggestion(
    val entryId: Long,
    val dictionaryId: Long,
    val dictionaryName: String,
    val headword: String,
    val dictionaryLabel: String,
)

data class UiSearchResult(
    val entryId: Long,
    val dictionaryId: Long,
    val dictionaryName: String,
    val headword: String,
    val dictionaryLabel: String,
)

data class UiArticleEntry(
    val entryId: Long,
    val headword: String,
    val dictionaryLabel: String,
    val articleHtml: String,
    val isFavorite: Boolean,
)
