package app.pocketdsl

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.cash.sqldelight.db.SqlDriver
import app.pocketdsl.archive.DslDzExtractor
import app.pocketdsl.archive.DslTextDecoder
import app.pocketdsl.archive.TarBz2Extractor
import app.pocketdsl.db.PocketDslDatabase
import app.pocketdsl.importer.DictionaryImporter
import app.pocketdsl.importer.DictionaryPackageImporter
import app.pocketdsl.importer.ImportProgress
import app.pocketdsl.importer.PackageImportProgress
import app.pocketdsl.platform.AndroidDriverFactory
import app.pocketdsl.storage.DictionaryEntry
import app.pocketdsl.storage.DictionaryMetadata
import app.pocketdsl.storage.DictionaryRepository
import app.pocketdsl.storage.SqlDelightDictionaryRepository
import app.pocketdsl.ui.PocketDslUiState
import app.pocketdsl.ui.UiArticleEntry
import app.pocketdsl.ui.UiSearchResult
import app.pocketdsl.ui.UiSuggestion

class AndroidPocketDslAppModel(context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val driver: SqlDriver = AndroidDriverFactory(context).createDriver()
    private val database = PocketDslDatabase(driver)
    private val repository: DictionaryRepository = SqlDelightDictionaryRepository(database)
    private val dictionaryImporter = DictionaryImporter(
        repository = repository,
        currentTimeMillis = { System.currentTimeMillis() },
    )
    private val dslDzExtractor = DslDzExtractor()
    private val packageImporter = DictionaryPackageImporter(
        archiveExtractor = TarBz2Extractor(),
        dslDzExtractor = dslDzExtractor,
        dictionaryImporter = dictionaryImporter,
    )

    var state by mutableStateOf(
        PocketDslUiState(dictionaryCount = repository.selectDictionaries().size),
    )
        private set

    fun onQueryChange(query: String) {
        val suggestions = if (query.isBlank()) {
            emptyList()
        } else {
            val dictionaries = dictionaryMap()
            repository.suggest(prefix = query, limit = 20)
                .map { suggestion ->
                    UiSuggestion(
                        entryId = suggestion.entryId,
                        headword = suggestion.headword,
                        dictionaryLabel = dictionaryLabel(dictionaries[suggestion.dictionaryId]),
                    )
                }
        }

        state = state.copy(
            query = query,
            suggestions = suggestions,
        )
    }

    fun searchSubmitted() {
        val query = state.query.trim()
        if (query.isBlank()) {
            state = state.copy(searchResults = emptyList())
            return
        }

        state = state.copy(searchResults = exactResults(query))
    }

    fun suggestionSelected(suggestion: UiSuggestion) {
        state = state.copy(
            query = suggestion.headword,
            searchResults = exactResults(suggestion.headword),
        )
        openEntry(entryId = suggestion.entryId, query = suggestion.headword)
    }

    fun resultSelected(result: UiSearchResult) {
        openEntry(entryId = result.entryId, query = state.query.ifBlank { result.headword })
    }

    fun toggleFavorite() {
        val selected = state.selectedEntry ?: return
        if (selected.isFavorite) {
            repository.removeFavorite(selected.entryId)
        } else {
            repository.addFavorite(
                entryId = selected.entryId,
                createdAt = System.currentTimeMillis(),
            )
        }
        state = state.copy(selectedEntry = selected.copy(isFavorite = !selected.isFavorite))
    }

    fun importFile(contentResolver: ContentResolver, uri: Uri) {
        updateState {
            it.copy(
                isImporting = true,
                importMessage = "Reading selected file...",
            )
        }

        val fileName = contentResolver.displayName(uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "selected-dictionary"

        try {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes()
            } ?: error("Could not open selected file")

            val message = when {
                fileName.endsWith(".dsl.dz", ignoreCase = true) -> importDslDz(fileName, bytes)
                fileName.endsWith(".dsl", ignoreCase = true) -> importDsl(fileName, bytes)
                fileName.endsWith(".tar.bz2", ignoreCase = true) -> importTarBz2(fileName, bytes)
                else -> "Unsupported file type. Select a .dsl, .dsl.dz, or .tar.bz2 file."
            }

            val dictionaryCount = repository.selectDictionaries().size
            updateState {
                it.copy(
                    dictionaryCount = dictionaryCount,
                    isImporting = false,
                    importMessage = message,
                    suggestions = if (it.query.isBlank()) emptyList() else suggestionsFor(it.query),
                )
            }
        } catch (cause: Throwable) {
            updateState {
                it.copy(
                    isImporting = false,
                    importMessage = friendlyError(cause),
                )
            }
        }
    }

    fun close() {
        driver.close()
    }

    private fun importDsl(fileName: String, bytes: ByteArray): String {
        updateState { it.copy(importMessage = "Decoding $fileName...") }
        val text = DslTextDecoder.decode(bytes)
        val result = dictionaryImporter.importDslText(
            sourceFileName = fileName,
            text = text,
            progress = ::onImportProgress,
        )
        return "Imported ${result.dictionaryName}: ${result.entryCount} entries, ${result.skippedCount} skipped."
    }

    private fun importDslDz(fileName: String, bytes: ByteArray): String {
        updateState { it.copy(importMessage = "Extracting $fileName...") }
        val text = dslDzExtractor.extractToText(bytes)
        val result = dictionaryImporter.importDslText(
            sourceFileName = fileName,
            text = text,
            progress = ::onImportProgress,
        )
        return "Imported ${result.dictionaryName}: ${result.entryCount} entries, ${result.skippedCount} skipped."
    }

    private fun importTarBz2(fileName: String, bytes: ByteArray): String {
        val result = packageImporter.importTarBz2Package(
            sourceFileName = fileName,
            bytes = bytes,
            progress = ::onPackageImportProgress,
        )

        val archiveFailure = result.archiveFailure
        if (archiveFailure != null) {
            return "Archive import failed: ${archiveFailure.message}"
        }

        return "Imported ${result.totalImportedDictionaries} dictionaries, " +
            "${result.totalImportedEntries} entries. Failed dictionaries: " +
            "${result.failedDictionaryCount}. Ignored files: ${result.ignoredFileCount}."
    }

    private fun onImportProgress(progress: ImportProgress) {
        val message = when (progress) {
            ImportProgress.Started -> "Starting import..."
            is ImportProgress.ExtractingArchive -> "Extracting ${progress.currentFile ?: "archive"}..."
            is ImportProgress.ParsingDictionary -> {
                val name = progress.dictionaryName ?: "dictionary"
                "Parsing $name..."
            }
            is ImportProgress.Indexing -> "Indexed ${progress.processedEntries} entries..."
            ImportProgress.Completed -> "Finishing import..."
            is ImportProgress.Failed -> "Import failed: ${progress.message}"
        }
        updateState { it.copy(importMessage = message) }
    }

    private fun onPackageImportProgress(progress: PackageImportProgress) {
        val message = when (progress) {
            is PackageImportProgress.Started -> "Starting ${progress.sourceFileName}..."
            is PackageImportProgress.ExtractingArchive -> "Extracting ${progress.sourceFileName}..."
            is PackageImportProgress.ArchiveExtracted -> {
                "Found ${progress.dictionaryFileCount} dictionaries; ignored ${progress.ignoredFileCount} files."
            }
            is PackageImportProgress.ImportingDictionary -> {
                "Importing ${progress.index}/${progress.total}: ${progress.path}"
            }
            is PackageImportProgress.DictionaryImported -> {
                "Imported ${progress.index}/${progress.total}: ${progress.entryCount} entries."
            }
            is PackageImportProgress.DictionaryFailed -> {
                "Failed ${progress.index}/${progress.total}: ${progress.message}"
            }
            is PackageImportProgress.Completed -> "Finishing package import..."
            is PackageImportProgress.Failed -> "Package import failed: ${progress.message}"
        }
        updateState { it.copy(importMessage = message) }
    }

    private fun exactResults(query: String): List<UiSearchResult> {
        val dictionaries = dictionaryMap()
        return repository.lookupExact(query).map { entry ->
            UiSearchResult(
                entryId = entry.id,
                headword = entry.headword,
                dictionaryLabel = dictionaryLabel(dictionaries[entry.dictionaryId]),
            )
        }
    }

    private fun suggestionsFor(query: String): List<UiSuggestion> {
        val dictionaries = dictionaryMap()
        return repository.suggest(prefix = query, limit = 20).map { suggestion ->
            UiSuggestion(
                entryId = suggestion.entryId,
                headword = suggestion.headword,
                dictionaryLabel = dictionaryLabel(dictionaries[suggestion.dictionaryId]),
            )
        }
    }

    private fun openEntry(entryId: Long, query: String) {
        val entry = repository.selectEntryById(entryId) ?: return
        repository.addHistoryItem(
            query = query,
            entryId = entry.id,
            createdAt = System.currentTimeMillis(),
        )
        val favoriteIds = repository.listFavorites().map { it.id }.toSet()
        state = state.copy(selectedEntry = entry.toUiArticleEntry(favoriteIds))
    }

    private fun DictionaryEntry.toUiArticleEntry(favoriteIds: Set<Long>): UiArticleEntry {
        val dictionary = dictionaryMap()[dictionaryId]
        return UiArticleEntry(
            entryId = id,
            headword = headword,
            dictionaryLabel = dictionaryLabel(dictionary),
            articleHtml = articleHtml,
            isFavorite = id in favoriteIds,
        )
    }

    private fun dictionaryMap(): Map<Long, DictionaryMetadata> =
        repository.selectDictionaries().associateBy { it.id }

    private fun dictionaryLabel(dictionary: DictionaryMetadata?): String {
        if (dictionary == null) return "Unknown dictionary"
        return if (dictionary.direction == "unknown") {
            dictionary.name
        } else {
            "${dictionary.name} · ${dictionary.direction}"
        }
    }

    private fun friendlyError(cause: Throwable): String =
        cause.message?.takeIf { it.isNotBlank() }?.let { "Import failed: $it" }
            ?: "Import failed. The selected file could not be imported."

    private fun updateState(transform: (PocketDslUiState) -> PocketDslUiState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            state = transform(state)
        } else {
            mainHandler.post {
                state = transform(state)
            }
        }
    }

    private fun ContentResolver.displayName(uri: Uri): String? {
        val cursor: Cursor = query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0) return null
            return it.getString(index)
        }
    }
}
