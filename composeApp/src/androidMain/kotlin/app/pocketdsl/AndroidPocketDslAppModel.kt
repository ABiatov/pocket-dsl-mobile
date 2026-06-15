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
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class AndroidPocketDslAppModel(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val driver: SqlDriver = AndroidDriverFactory(appContext).createDriver()
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

        var cachedFile: File? = null

        try {
            val fileInfo = contentResolver.fileInfo(uri)
            val fileName = fileInfo.displayName ?: uri.inferredFileName()
            val importKind = ImportKind.fromFileName(fileName)
            if (importKind == null) {
                updateState {
                    it.copy(
                        isImporting = false,
                        importMessage = "Unsupported file type. Select a .dsl, .dsl.dz, or .tar.bz2 file.",
                    )
                }
                return
            }

            cachedFile = copyUriToCache(
                contentResolver = contentResolver,
                uri = uri,
                fileName = fileName,
                expectedSizeBytes = fileInfo.sizeBytes,
            )

            val message = when (importKind) {
                ImportKind.DSL_DZ -> importDslDz(fileName, cachedFile)
                ImportKind.DSL -> importDsl(fileName, cachedFile)
                ImportKind.TAR_BZ2 -> importTarBz2(fileName, cachedFile)
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
        } finally {
            cachedFile?.delete()
        }
    }

    fun close() {
        driver.close()
    }

    private fun importDsl(fileName: String, file: File): String {
        updateState { it.copy(importMessage = "Decoding $fileName...") }
        val bytes = file.readImportBytes(fileName)
        val text = DslTextDecoder.decode(bytes)
        val result = dictionaryImporter.importDslText(
            sourceFileName = fileName,
            text = text,
            progress = ::onImportProgress,
        )
        return "Imported ${result.dictionaryName}: ${result.entryCount} entries, ${result.skippedCount} skipped."
    }

    private fun importDslDz(fileName: String, file: File): String {
        updateState { it.copy(importMessage = "Extracting $fileName...") }
        val bytes = file.readImportBytes(fileName)
        val text = dslDzExtractor.extractToText(bytes)
        val result = dictionaryImporter.importDslText(
            sourceFileName = fileName,
            text = text,
            progress = ::onImportProgress,
        )
        return "Imported ${result.dictionaryName}: ${result.entryCount} entries, ${result.skippedCount} skipped."
    }

    private fun importTarBz2(fileName: String, file: File): String {
        val bytes = file.readImportBytes(fileName)
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
        when (cause) {
            is ImportFileTooLargeException -> "Import failed: ${cause.message}"
            is SecurityException -> {
                "Import failed: Android denied access to the selected file. Re-open it from the file picker and try again."
            }
            is FileNotFoundException -> {
                "Import failed: The selected file could not be found. Re-open it from the file picker and try again."
            }
            is IOException -> {
                "Import failed: Could not read the selected file. Re-open it from the file picker and try again."
            }
            is OutOfMemoryError -> {
                "Import failed: The selected file is too large for this device. Try a smaller dictionary package."
            }
            else -> cause.message?.takeIf { it.isNotBlank() }?.let { "Import failed: $it" }
                ?: "Import failed. The selected file could not be imported."
        }

    private fun copyUriToCache(
        contentResolver: ContentResolver,
        uri: Uri,
        fileName: String,
        expectedSizeBytes: Long?,
    ): File {
        expectedSizeBytes?.let { size ->
            if (size > MAX_ANDROID_IMPORT_BYTES) {
                throw ImportFileTooLargeException(fileTooLargeMessage(size))
            }
        }

        val cachedFile = File.createTempFile("pocketdsl-import-", ".tmp", appContext.cacheDir)
        var copiedBytes = 0L
        var nextProgressBytes = READ_PROGRESS_INTERVAL_BYTES

        try {
            val input = contentResolver.openInputStream(uri)
                ?: throw IOException("Could not open selected file")
            input.use { source ->
                cachedFile.outputStream().use { destination ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE_BYTES)
                    while (true) {
                        val read = source.read(buffer)
                        if (read == -1) break

                        copiedBytes += read.toLong()
                        if (copiedBytes > MAX_ANDROID_IMPORT_BYTES) {
                            throw ImportFileTooLargeException(fileTooLargeMessage(copiedBytes))
                        }

                        destination.write(buffer, 0, read)

                        if (copiedBytes >= nextProgressBytes) {
                            updateState {
                                it.copy(
                                    importMessage = readProgressMessage(
                                        fileName = fileName,
                                        copiedBytes = copiedBytes,
                                        expectedSizeBytes = expectedSizeBytes,
                                    ),
                                )
                            }
                            nextProgressBytes += READ_PROGRESS_INTERVAL_BYTES
                        }
                    }
                }
            }
            return cachedFile
        } catch (cause: Throwable) {
            cachedFile.delete()
            throw cause
        }
    }

    private fun File.readImportBytes(fileName: String): ByteArray {
        val size = length()
        if (size > MAX_ANDROID_IMPORT_BYTES) {
            throw ImportFileTooLargeException(fileTooLargeMessage(size))
        }
        if (size > Int.MAX_VALUE) {
            throw ImportFileTooLargeException(fileTooLargeMessage(size))
        }

        updateState {
            it.copy(importMessage = "Loading ${fileName.importDisplayName()}...")
        }

        return try {
            readBytes()
        } catch (cause: OutOfMemoryError) {
            throw ImportFileTooLargeException(
                "The selected file is too large for this device. Try a smaller dictionary package.",
                cause,
            )
        }
    }

    private fun updateState(transform: (PocketDslUiState) -> PocketDslUiState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            state = transform(state)
        } else {
            mainHandler.post {
                state = transform(state)
            }
        }
    }

    private fun ContentResolver.fileInfo(uri: Uri): SelectedFileInfo {
        val cursor: Cursor = query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )
            ?: return SelectedFileInfo()

        cursor.use {
            if (!it.moveToFirst()) return SelectedFileInfo()

            val displayName = it.stringOrNull(OpenableColumns.DISPLAY_NAME)
                ?.takeIf { name -> name.isNotBlank() }
            val sizeBytes = it.longOrNull(OpenableColumns.SIZE)
                ?.takeIf { size -> size >= 0L }

            return SelectedFileInfo(displayName = displayName, sizeBytes = sizeBytes)
        }
    }

    private fun Cursor.stringOrNull(columnName: String): String? {
        val index = getColumnIndex(columnName)
        if (index < 0 || isNull(index)) return null
        return getString(index)
    }

    private fun Cursor.longOrNull(columnName: String): Long? {
        val index = getColumnIndex(columnName)
        if (index < 0 || isNull(index)) return null
        return getLong(index)
    }

    private fun Uri.inferredFileName(): String {
        val decodedPath = Uri.decode(lastPathSegment ?: "")
        return decodedPath.substringAfterLast('/')
            .takeIf { it.isNotBlank() }
            ?: "selected-dictionary"
    }

    private fun readProgressMessage(
        fileName: String,
        copiedBytes: Long,
        expectedSizeBytes: Long?,
    ): String {
        val copied = copiedBytes.toMiBString()
        val name = fileName.importDisplayName()
        if (expectedSizeBytes == null || expectedSizeBytes <= 0L) {
            return "Reading $name: $copied..."
        }

        val total = expectedSizeBytes.toMiBString()
        val percent = ((copiedBytes * 100L) / expectedSizeBytes).coerceIn(0L, 100L)
        return "Reading $name: $copied of $total ($percent%)..."
    }

    private fun fileTooLargeMessage(sizeBytes: Long): String =
        "The selected file is ${sizeBytes.toMiBString()}, which exceeds the Android MVP import limit of " +
            "${MAX_ANDROID_IMPORT_BYTES.toMiBString()}."

    private fun Long.toMiBString(): String {
        val mib = this / BYTES_PER_MIB
        val remainder = ((this % BYTES_PER_MIB) * 10L) / BYTES_PER_MIB
        return if (mib == 0L && this > 0L) {
            "<1 MiB"
        } else if (remainder == 0L) {
            "$mib MiB"
        } else {
            "$mib.$remainder MiB"
        }
    }

    private fun String.importDisplayName(): String =
        if (length <= MAX_IMPORT_MESSAGE_FILE_NAME_LENGTH) {
            this
        } else {
            take(32) + "..." + takeLast(32)
        }

    private data class SelectedFileInfo(
        val displayName: String? = null,
        val sizeBytes: Long? = null,
    )

    private enum class ImportKind {
        DSL,
        DSL_DZ,
        TAR_BZ2,
        ;

        companion object {
            fun fromFileName(fileName: String): ImportKind? =
                when {
                    fileName.endsWith(".dsl.dz", ignoreCase = true) -> DSL_DZ
                    fileName.endsWith(".dsl", ignoreCase = true) -> DSL
                    fileName.endsWith(".tar.bz2", ignoreCase = true) -> TAR_BZ2
                    else -> null
                }
        }
    }

    private class ImportFileTooLargeException(
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    private companion object {
        const val COPY_BUFFER_SIZE_BYTES = 1024 * 1024
        const val READ_PROGRESS_INTERVAL_BYTES = 8L * 1024L * 1024L
        const val MAX_ANDROID_IMPORT_BYTES = 512L * 1024L * 1024L
        const val BYTES_PER_MIB = 1024L * 1024L
        const val MAX_IMPORT_MESSAGE_FILE_NAME_LENGTH = 72
    }
}
