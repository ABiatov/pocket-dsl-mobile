package app.pocketdsl.importer

import app.pocketdsl.archive.ArchiveEntry
import app.pocketdsl.archive.ArchiveExtractor
import app.pocketdsl.archive.DslDzExtractor
import app.pocketdsl.archive.DslTextDecoder

class DictionaryPackageImporter(
    private val archiveExtractor: ArchiveExtractor,
    private val dslDzExtractor: DslDzExtractor,
    private val dictionaryImporter: DictionaryImporter,
) {
    fun importTarBz2Package(
        sourceFileName: String,
        bytes: ByteArray,
        progress: ((PackageImportProgress) -> Unit)? = null,
    ): PackageImportResult {
        fun emit(event: PackageImportProgress) {
            progress?.invoke(event)
        }

        emit(PackageImportProgress.Started(sourceFileName))
        emit(PackageImportProgress.ExtractingArchive(sourceFileName))

        val entries = try {
            archiveExtractor.extract(bytes)
        } catch (cause: Throwable) {
            val failure = PackageImportFailure(
                message = cause.message ?: "Archive extraction failed",
                cause = cause,
            )
            val result = PackageImportResult(
                sourceFileName = sourceFileName,
                dictionaries = emptyList(),
                ignoredFileCount = 0,
                archiveFailure = failure,
            )
            emit(PackageImportProgress.Failed(sourceFileName, failure.message, cause))
            return result
        }

        val dictionaryEntries = entries
            .mapNotNull { entry -> entry.toDictionaryCandidateOrNull() }
            .sortedBy { it.entry.path }
        val ignoredFileCount = entries.size - dictionaryEntries.size

        emit(
            PackageImportProgress.ArchiveExtracted(
                sourceFileName = sourceFileName,
                totalFileCount = entries.size,
                dictionaryFileCount = dictionaryEntries.size,
                ignoredFileCount = ignoredFileCount,
            ),
        )

        val dictionaryResults = mutableListOf<PackageDictionaryImportResult>()
        val totalDictionaries = dictionaryEntries.size

        dictionaryEntries.forEachIndexed { index, candidate ->
            val position = index + 1
            emit(
                PackageImportProgress.ImportingDictionary(
                    path = candidate.entry.path,
                    index = position,
                    total = totalDictionaries,
                    kind = candidate.kind,
                ),
            )

            val imported = try {
                val text = when (candidate.kind) {
                    PackageDictionaryKind.DSL -> DslTextDecoder.decode(candidate.entry.bytes)
                    PackageDictionaryKind.DSL_DZ -> dslDzExtractor.extractToText(candidate.entry.bytes)
                }

                val importResult = dictionaryImporter.importDslText(
                    sourceFileName = candidate.entry.path,
                    text = text,
                )

                PackageDictionaryImportResult(
                    path = candidate.entry.path,
                    kind = candidate.kind,
                    importResult = importResult,
                    failure = null,
                )
            } catch (cause: Throwable) {
                PackageDictionaryImportResult(
                    path = candidate.entry.path,
                    kind = candidate.kind,
                    importResult = null,
                    failure = PackageImportFailure(
                        message = cause.message ?: "Dictionary import failed",
                        cause = cause,
                    ),
                )
            }

            dictionaryResults += imported

            if (imported.isSuccess) {
                emit(
                    PackageImportProgress.DictionaryImported(
                        path = imported.path,
                        index = position,
                        total = totalDictionaries,
                        kind = imported.kind,
                        entryCount = imported.importResult?.entryCount ?: 0,
                        skippedCount = imported.importResult?.skippedCount ?: 0,
                    ),
                )
            } else {
                emit(
                    PackageImportProgress.DictionaryFailed(
                        path = imported.path,
                        index = position,
                        total = totalDictionaries,
                        kind = imported.kind,
                        message = imported.failure?.message ?: "Dictionary import failed",
                        cause = imported.failure?.cause,
                    ),
                )
            }
        }

        val result = PackageImportResult(
            sourceFileName = sourceFileName,
            dictionaries = dictionaryResults,
            ignoredFileCount = ignoredFileCount,
            archiveFailure = null,
        )
        emit(PackageImportProgress.Completed(result))
        return result
    }

    private fun ArchiveEntry.toDictionaryCandidateOrNull(): DictionaryCandidate? {
        val lowerPath = path.lowercase()
        return when {
            lowerPath.endsWith(".dsl.dz") -> DictionaryCandidate(this, PackageDictionaryKind.DSL_DZ)
            lowerPath.endsWith(".dsl") -> DictionaryCandidate(this, PackageDictionaryKind.DSL)
            else -> null
        }
    }

    private data class DictionaryCandidate(
        val entry: ArchiveEntry,
        val kind: PackageDictionaryKind,
    )
}

data class PackageImportResult(
    val sourceFileName: String,
    val dictionaries: List<PackageDictionaryImportResult>,
    val ignoredFileCount: Int,
    val archiveFailure: PackageImportFailure? = null,
) {
    val isSuccess: Boolean
        get() = archiveFailure == null

    val totalImportedDictionaries: Int
        get() = dictionaries.count { it.isSuccess }

    val totalImportedEntries: Long
        get() = dictionaries.sumOf { it.importResult?.entryCount ?: 0 }

    val totalSkippedEntries: Long
        get() = dictionaries.sumOf { it.importResult?.skippedCount ?: 0 }

    val failedDictionaryCount: Int
        get() = dictionaries.count { !it.isSuccess }
}

data class PackageDictionaryImportResult(
    val path: String,
    val kind: PackageDictionaryKind,
    val importResult: ImportResult?,
    val failure: PackageImportFailure?,
) {
    val isSuccess: Boolean
        get() = importResult != null && failure == null
}

enum class PackageDictionaryKind {
    DSL,
    DSL_DZ,
}

data class PackageImportFailure(
    val message: String,
    val cause: Throwable? = null,
)

sealed interface PackageImportProgress {
    data class Started(val sourceFileName: String) : PackageImportProgress

    data class ExtractingArchive(val sourceFileName: String) : PackageImportProgress

    data class ArchiveExtracted(
        val sourceFileName: String,
        val totalFileCount: Int,
        val dictionaryFileCount: Int,
        val ignoredFileCount: Int,
    ) : PackageImportProgress

    data class ImportingDictionary(
        val path: String,
        val index: Int,
        val total: Int,
        val kind: PackageDictionaryKind,
    ) : PackageImportProgress

    data class DictionaryImported(
        val path: String,
        val index: Int,
        val total: Int,
        val kind: PackageDictionaryKind,
        val entryCount: Long,
        val skippedCount: Long,
    ) : PackageImportProgress

    data class DictionaryFailed(
        val path: String,
        val index: Int,
        val total: Int,
        val kind: PackageDictionaryKind,
        val message: String,
        val cause: Throwable? = null,
    ) : PackageImportProgress

    data class Completed(val result: PackageImportResult) : PackageImportProgress

    data class Failed(
        val sourceFileName: String,
        val message: String,
        val cause: Throwable? = null,
    ) : PackageImportProgress
}
