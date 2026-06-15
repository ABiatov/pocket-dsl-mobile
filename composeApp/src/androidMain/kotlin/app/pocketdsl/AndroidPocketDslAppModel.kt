package app.pocketdsl

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.cash.sqldelight.db.SqlDriver
import app.pocketdsl.archive.ArchiveExtractionException
import app.pocketdsl.archive.ArchiveExtractionLimits
import app.pocketdsl.archive.DslDzExtractor
import app.pocketdsl.archive.DslDzExtractionException
import app.pocketdsl.archive.DslDzLimits
import app.pocketdsl.archive.DslDzTextStream
import app.pocketdsl.archive.DslTextDecoder
import app.pocketdsl.archive.DslTextDecodingException
import app.pocketdsl.archive.TarBz2Extractor
import app.pocketdsl.db.PocketDslDatabase
import app.pocketdsl.importer.DictionaryImportException
import app.pocketdsl.importer.DictionaryImportStage
import app.pocketdsl.importer.DictionaryImporter
import app.pocketdsl.importer.DictionaryPackageImporter
import app.pocketdsl.importer.ImportResult
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
    private val dslDzExtractor = DslDzExtractor(ANDROID_DSL_DZ_LIMITS)
    private val dslDzTextStream = DslDzTextStream(ANDROID_DSL_DZ_LIMITS)
    private val packageImporter = DictionaryPackageImporter(
        archiveExtractor = TarBz2Extractor(ANDROID_ARCHIVE_LIMITS),
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
                    val dictionary = dictionaries[suggestion.dictionaryId]
                    UiSuggestion(
                        entryId = suggestion.entryId,
                        dictionaryId = suggestion.dictionaryId,
                        dictionaryName = dictionaryName(dictionary),
                        headword = suggestion.headword,
                        dictionaryLabel = dictionaryLabel(dictionary),
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
        val results = runCatching {
            exactResults(suggestion.headword)
        }.onFailure { cause ->
            logArticleOpenFailure(
                action = "suggestion_exact_lookup",
                entryId = suggestion.entryId,
                headword = suggestion.headword,
                dictionaryId = suggestion.dictionaryId,
                dictionaryName = suggestion.dictionaryName,
                cause = cause,
            )
        }.getOrDefault(emptyList())

        state = state.copy(
            query = suggestion.headword,
            searchResults = results,
        )
        openEntry(
            entryId = suggestion.entryId,
            query = suggestion.headword,
            requestedHeadword = suggestion.headword,
            requestedDictionaryId = suggestion.dictionaryId,
            requestedDictionaryName = suggestion.dictionaryName,
        )
    }

    fun resultSelected(result: UiSearchResult) {
        openEntry(
            entryId = result.entryId,
            query = state.query.ifBlank { result.headword },
            requestedHeadword = result.headword,
            requestedDictionaryId = result.dictionaryId,
            requestedDictionaryName = result.dictionaryName,
        )
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
        val diagnostics = ImportDiagnostics()

        try {
            val fileInfo = contentResolver.fileInfo(uri)
            val fileName = fileInfo.displayName ?: uri.inferredFileName()
            val importKind = ImportKind.fromFileName(fileName)
            diagnostics.fileName = fileName
            diagnostics.importKind = importKind
            diagnostics.reportedSelectedBytes = fileInfo.sizeBytes
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
                selectedLimitBytes = importKind.selectedInputLimitBytes,
            )
            diagnostics.selectedFileBytes = cachedFile.length()
            logImportStarted(diagnostics)

            val message = when (importKind) {
                ImportKind.DSL_DZ -> importDslDz(fileName, cachedFile, diagnostics)
                ImportKind.DSL -> importDsl(fileName, cachedFile, diagnostics)
                ImportKind.TAR_BZ2 -> importTarBz2(fileName, cachedFile, diagnostics)
            }
            logImportSucceeded(diagnostics)

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
            logImportFailure(cause, diagnostics)
            updateState {
                it.copy(
                    isImporting = false,
                    importMessage = friendlyError(cause, diagnostics),
                )
            }
        } finally {
            cachedFile?.delete()
        }
    }

    fun close() {
        driver.close()
    }

    private fun importDsl(fileName: String, file: File, diagnostics: ImportDiagnostics): String {
        updateState { it.copy(importMessage = "Decoding $fileName...") }
        val bytes = file.readImportBytes(
            fileName = fileName,
            selectedLimitBytes = ImportKind.DSL.selectedInputLimitBytes,
        )
        diagnostics.selectedFileBytes = bytes.size.toLong()
        diagnostics.decompressedDslBytes = bytes.size.toLong()
        val decoded = DslTextDecoder.decodeWithInfo(bytes)
        diagnostics.decompressedDslChars = decoded.text.length.toLong()
        diagnostics.detectedEncoding = decoded.encoding.displayName
        val result = dictionaryImporter.importDslText(
            sourceFileName = fileName,
            text = decoded.text,
            progress = progressLogger(diagnostics),
        )
        diagnostics.dictionaryName = result.dictionaryName
        diagnostics.importedEntryCount = result.entryCount
        return result.importSuccessMessage()
    }

    private fun importDslDz(fileName: String, file: File, diagnostics: ImportDiagnostics): String {
        updateState { it.copy(importMessage = "Extracting $fileName...") }
        val size = file.length()
        if (size > ImportKind.DSL_DZ.selectedInputLimitBytes) {
            throw ImportFileTooLargeException(
                fileTooLargeMessage(size, ImportKind.DSL_DZ.selectedInputLimitBytes),
            )
        }
        diagnostics.selectedFileBytes = size

        val result = file.inputStream().use { input ->
            dslDzTextStream.open(input, compressedByteCount = size).use { streamingText ->
                diagnostics.detectedEncoding = streamingText.encoding.displayName
                val lines = streamingText.lines.onEach {
                    diagnostics.decompressedDslBytes = streamingText.counters.decompressedByteCount
                    diagnostics.decompressedDslChars = streamingText.counters.decompressedCharCount
                }
                try {
                    dictionaryImporter.importDslLines(
                        sourceFileName = fileName,
                        lines = lines,
                        progress = progressLogger(diagnostics),
                    )
                } finally {
                    diagnostics.decompressedDslBytes = streamingText.counters.decompressedByteCount
                    diagnostics.decompressedDslChars = streamingText.counters.decompressedCharCount
                }
            }
        }
        diagnostics.dictionaryName = result.dictionaryName
        diagnostics.importedEntryCount = result.entryCount
        return result.importSuccessMessage()
    }

    private fun importTarBz2(fileName: String, file: File, diagnostics: ImportDiagnostics): String {
        val bytes = file.readImportBytes(
            fileName = fileName,
            selectedLimitBytes = ImportKind.TAR_BZ2.selectedInputLimitBytes,
        )
        diagnostics.selectedFileBytes = bytes.size.toLong()
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

    private fun progressLogger(diagnostics: ImportDiagnostics): (ImportProgress) -> Unit = { progress ->
        when (progress) {
            is ImportProgress.ParsingDictionary -> {
                diagnostics.dictionaryName = progress.dictionaryName ?: diagnostics.dictionaryName
            }
            is ImportProgress.Indexing -> {
                diagnostics.importedEntryCount = progress.processedEntries
            }
            else -> Unit
        }
        onImportProgress(progress)
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

    private fun ImportResult.importSuccessMessage(): String {
        val warningSummary = warnings
            .takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = " Warnings: ") { warning ->
                "${warning.count} ${warning.reason.name.lowercase()}."
            }
            ?: ""

        return "Imported $dictionaryName: $entryCount entries, $skippedCount skipped.$warningSummary"
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
            val dictionary = dictionaries[entry.dictionaryId]
            UiSearchResult(
                entryId = entry.id,
                dictionaryId = entry.dictionaryId,
                dictionaryName = dictionaryName(dictionary),
                headword = entry.headword,
                dictionaryLabel = dictionaryLabel(dictionary),
            )
        }
    }

    private fun suggestionsFor(query: String): List<UiSuggestion> {
        val dictionaries = dictionaryMap()
        return repository.suggest(prefix = query, limit = 20).map { suggestion ->
            val dictionary = dictionaries[suggestion.dictionaryId]
            UiSuggestion(
                entryId = suggestion.entryId,
                dictionaryId = suggestion.dictionaryId,
                dictionaryName = dictionaryName(dictionary),
                headword = suggestion.headword,
                dictionaryLabel = dictionaryLabel(dictionary),
            )
        }
    }

    private fun openEntry(
        entryId: Long,
        query: String,
        requestedHeadword: String,
        requestedDictionaryId: Long,
        requestedDictionaryName: String,
    ) {
        try {
            val entry = repository.selectEntryById(entryId)
            if (entry == null) {
                Log.w(
                    LOG_TAG,
                    "Article entry missing: entryId=$entryId headword=$requestedHeadword " +
                        "dictionaryId=$requestedDictionaryId dictionaryName=$requestedDictionaryName",
                )
                state = state.copy(
                    importMessage = "Article could not be opened because the entry is no longer available.",
                )
                return
            }

            val dictionary = dictionaryMap()[entry.dictionaryId]
            val dictionaryName = dictionaryName(dictionary)
            Log.i(
                LOG_TAG,
                "Opening article: entryId=${entry.id} headword=${entry.headword} " +
                    "dictionaryId=${entry.dictionaryId} dictionaryName=$dictionaryName",
            )

            runCatching {
                repository.addHistoryItem(
                    query = query,
                    entryId = entry.id,
                    createdAt = System.currentTimeMillis(),
                )
            }.onFailure { cause ->
                logArticleOpenFailure(
                    action = "add_history",
                    entryId = entry.id,
                    headword = entry.headword,
                    dictionaryId = entry.dictionaryId,
                    dictionaryName = dictionaryName,
                    cause = cause,
                )
            }

            val favoriteIds = runCatching {
                repository.listFavorites().map { it.id }.toSet()
            }.onFailure { cause ->
                logArticleOpenFailure(
                    action = "list_favorites",
                    entryId = entry.id,
                    headword = entry.headword,
                    dictionaryId = entry.dictionaryId,
                    dictionaryName = dictionaryName,
                    cause = cause,
                )
            }.getOrDefault(emptySet())

            state = state.copy(selectedEntry = entry.toUiArticleEntry(favoriteIds, dictionary))
        } catch (cause: Throwable) {
            logArticleOpenFailure(
                action = "open_article",
                entryId = entryId,
                headword = requestedHeadword,
                dictionaryId = requestedDictionaryId,
                dictionaryName = requestedDictionaryName,
                cause = cause,
            )
            state = state.copy(
                importMessage = "Could not open article for \"$requestedHeadword\". Try searching again.",
            )
        }
    }

    private fun DictionaryEntry.toUiArticleEntry(
        favoriteIds: Set<Long>,
        dictionary: DictionaryMetadata?,
    ): UiArticleEntry {
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

    private fun dictionaryName(dictionary: DictionaryMetadata?): String =
        dictionary?.name ?: "Unknown dictionary"

    private fun dictionaryLabel(dictionary: DictionaryMetadata?): String {
        if (dictionary == null) return "Unknown dictionary"
        return if (dictionary.direction == "unknown") {
            dictionary.name
        } else {
            "${dictionary.name} · ${dictionary.direction}"
        }
    }

    private fun friendlyError(cause: Throwable, diagnostics: ImportDiagnostics): String =
        when (cause) {
            is ImportFileTooLargeException -> when (cause.stage) {
                ImportSizeFailureStage.SELECTED_FILE_TOO_LARGE -> {
                    "Import failed: selected file too large. ${cause.message}"
                }
                ImportSizeFailureStage.DECOMPRESSED_DSL_TOO_LARGE -> {
                    "Import failed: decompressed DSL too large. ${cause.message}"
                }
            }
            is DslDzExtractionException -> "Import failed: ${friendlyDslDzError(cause)}"
            is DslTextDecodingException -> "Import failed: decoding failed. ${cause.message}"
            is DictionaryImportException -> when (cause.stage) {
                DictionaryImportStage.DATABASE_INSERT -> {
                    "Import failed: database insert failure after ${diagnostics.importedEntryCount} imported entries. " +
                        cause.sanitizedMessage("The dictionary could not be saved.")
                }
            }
            is ArchiveExtractionException -> "Import failed: ${formatByteLimits(cause.message)}"
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
                "Import failed: unexpected importer failure: the file could not be loaded in memory. Android MVP limits are " +
                    "${MAX_ANDROID_SELECTED_DSL_DZ_BYTES.toMiBString()} selected compressed input and " +
                    "${MAX_ANDROID_DECOMPRESSED_DSL_TEXT_BYTES.toMiBString()} decompressed DSL text."
            }
            else -> "Import failed: unexpected importer failure. " +
                cause.sanitizedMessage("The selected file could not be imported.")
        }

    private fun friendlyDslDzError(cause: DslDzExtractionException): String {
        val message = cause.message.orEmpty()
        return when {
            message.contains("compressed input exceeds limit", ignoreCase = true) -> {
                "selected file too large. ${formatByteLimits(message)}"
            }
            message.contains("decompressed output exceeds limit", ignoreCase = true) -> {
                "decompressed DSL too large. ${formatByteLimits(message)}"
            }
            message.contains("Invalid DSL.DZ gzip data", ignoreCase = true) -> {
                "invalid gzip/dictzip data. The selected .dsl.dz file could not be decompressed."
            }
            message.contains("not valid UTF-8 or UTF-16", ignoreCase = true) -> {
                "decoding failed. Decompressed DSL text is not valid UTF-8 or UTF-16 with BOM."
            }
            else -> formatByteLimits(message.takeIf { it.isNotBlank() })
        }
    }

    private fun copyUriToCache(
        contentResolver: ContentResolver,
        uri: Uri,
        fileName: String,
        expectedSizeBytes: Long?,
        selectedLimitBytes: Long,
    ): File {
        expectedSizeBytes?.let { size ->
            if (size > selectedLimitBytes) {
                throw ImportFileTooLargeException(fileTooLargeMessage(size, selectedLimitBytes))
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
                        if (copiedBytes > selectedLimitBytes) {
                            throw ImportFileTooLargeException(fileTooLargeMessage(copiedBytes, selectedLimitBytes))
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

    private fun File.readImportBytes(
        fileName: String,
        selectedLimitBytes: Long,
    ): ByteArray {
        val size = length()
        if (size > selectedLimitBytes) {
            throw ImportFileTooLargeException(fileTooLargeMessage(size, selectedLimitBytes))
        }
        if (size > Int.MAX_VALUE) {
            throw ImportFileTooLargeException(fileTooLargeMessage(size, selectedLimitBytes))
        }

        updateState {
            it.copy(importMessage = "Loading ${fileName.importDisplayName()}...")
        }

        return try {
            readBytes()
        } catch (cause: OutOfMemoryError) {
            throw ImportFileTooLargeException(
                "The selected file is ${size.toMiBString()}, which could not be loaded in memory. " +
                    "Android MVP selected input limit is ${selectedLimitBytes.toMiBString()}.",
                cause = cause,
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

    private fun fileTooLargeMessage(sizeBytes: Long, limitBytes: Long): String =
        "The selected file is ${sizeBytes.toMiBString()}, which exceeds the Android MVP import limit of " +
            "${limitBytes.toMiBString()}."

    private fun formatByteLimits(message: String?): String {
        val source = message?.takeIf { it.isNotBlank() }
            ?: return "The selected file could not be imported."

        return BYTE_LIMIT_PATTERN.replace(source) { match ->
            val actualBytes = match.groupValues[1].toLongOrNull()
            val limitBytes = match.groupValues[2].toLongOrNull()
            if (actualBytes == null || limitBytes == null) {
                match.value
            } else {
                "${actualBytes.toMiBString()} > ${limitBytes.toMiBString()}"
            }
        }
    }

    private fun logArticleOpenFailure(
        action: String,
        entryId: Long,
        headword: String,
        dictionaryId: Long,
        dictionaryName: String,
        cause: Throwable,
    ) {
        Log.e(
            LOG_TAG,
            "Article open failed: action=$action entryId=$entryId headword=$headword " +
                "dictionaryId=$dictionaryId dictionaryName=$dictionaryName " +
                "exception=${cause::class.java.simpleName}: ${cause.message}",
        )
    }

    private fun logImportStarted(diagnostics: ImportDiagnostics) {
        Log.i(
            LOG_TAG,
            "Import started: ${diagnostics.toLogFields()}",
        )
    }

    private fun logImportSucceeded(diagnostics: ImportDiagnostics) {
        Log.i(
            LOG_TAG,
            "Import succeeded: ${diagnostics.toLogFields()}",
        )
    }

    private fun logImportFailure(cause: Throwable, diagnostics: ImportDiagnostics) {
        Log.e(
            LOG_TAG,
            "Import failed: ${diagnostics.toLogFields()} exception=${cause::class.java.name} " +
                "message=${cause.sanitizedMessage("<none>")}",
            cause,
        )
    }

    private fun ImportDiagnostics.toLogFields(): String =
        "kind=${importKind?.displayName ?: "unknown"} " +
            "fileName=${fileName.sanitizedLogValue()} " +
            "reportedSelectedBytes=${reportedSelectedBytes ?: "unknown"} " +
            "selectedFileBytes=${selectedFileBytes ?: "unknown"} " +
            "decompressedDslBytes=${decompressedDslBytes ?: "unknown"} " +
            "decompressedDslChars=${decompressedDslChars ?: "unknown"} " +
            "encoding=${detectedEncoding ?: "unknown"} " +
            "dictionaryName=${dictionaryName.sanitizedLogValue()} " +
            "importedEntryCount=$importedEntryCount"

    private fun Throwable.sanitizedMessage(fallback: String): String =
        message
            ?.replace(Regex("[\\r\\n\\t]+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: fallback

    private fun String?.sanitizedLogValue(): String =
        this
            ?.replace(Regex("[\\r\\n\\t]+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"

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

    private data class ImportDiagnostics(
        var importKind: ImportKind? = null,
        var fileName: String? = null,
        var reportedSelectedBytes: Long? = null,
        var selectedFileBytes: Long? = null,
        var decompressedDslBytes: Long? = null,
        var decompressedDslChars: Long? = null,
        var detectedEncoding: String? = null,
        var dictionaryName: String? = null,
        var importedEntryCount: Long = 0,
    )

    private enum class ImportKind(val displayName: String) {
        DSL(".dsl") {
            override val selectedInputLimitBytes: Long = MAX_ANDROID_SELECTED_DSL_BYTES
        },
        DSL_DZ(".dsl.dz") {
            override val selectedInputLimitBytes: Long = MAX_ANDROID_SELECTED_DSL_DZ_BYTES
        },
        TAR_BZ2(".tar.bz2") {
            override val selectedInputLimitBytes: Long = MAX_ANDROID_SELECTED_TAR_BZ2_BYTES
        },
        ;

        abstract val selectedInputLimitBytes: Long

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
        val stage: ImportSizeFailureStage = ImportSizeFailureStage.SELECTED_FILE_TOO_LARGE,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    private enum class ImportSizeFailureStage {
        SELECTED_FILE_TOO_LARGE,
        DECOMPRESSED_DSL_TOO_LARGE,
    }

    private companion object {
        const val LOG_TAG = "PocketDsl"
        const val COPY_BUFFER_SIZE_BYTES = 1024 * 1024
        const val READ_PROGRESS_INTERVAL_BYTES = 8L * 1024L * 1024L
        const val BYTES_PER_MIB = 1024L * 1024L
        const val MAX_IMPORT_MESSAGE_FILE_NAME_LENGTH = 72
        const val MAX_ANDROID_SELECTED_DSL_BYTES = 768L * BYTES_PER_MIB
        const val MAX_ANDROID_SELECTED_DSL_DZ_BYTES = 1024L * BYTES_PER_MIB
        const val MAX_ANDROID_SELECTED_TAR_BZ2_BYTES = 1024L * BYTES_PER_MIB
        const val MAX_ANDROID_DECOMPRESSED_DSL_TEXT_BYTES = 1024L * BYTES_PER_MIB
        const val MAX_ANDROID_ARCHIVE_TOTAL_EXTRACTED_BYTES = 2L * 1024L * BYTES_PER_MIB
        const val MAX_ANDROID_ARCHIVE_ENTRY_BYTES = 1024L * BYTES_PER_MIB

        val ANDROID_DSL_DZ_LIMITS = DslDzLimits(
            maxCompressedInputBytes = MAX_ANDROID_SELECTED_DSL_DZ_BYTES,
            maxDecompressedOutputBytes = MAX_ANDROID_DECOMPRESSED_DSL_TEXT_BYTES,
        )
        val ANDROID_ARCHIVE_LIMITS = ArchiveExtractionLimits(
            maxCompressedSizeBytes = MAX_ANDROID_SELECTED_TAR_BZ2_BYTES,
            maxTotalExtractedSizeBytes = MAX_ANDROID_ARCHIVE_TOTAL_EXTRACTED_BYTES,
            maxFileSizeBytes = MAX_ANDROID_ARCHIVE_ENTRY_BYTES,
        )
        val BYTE_LIMIT_PATTERN = Regex("(\\d+) bytes > (\\d+) bytes")
    }
}
