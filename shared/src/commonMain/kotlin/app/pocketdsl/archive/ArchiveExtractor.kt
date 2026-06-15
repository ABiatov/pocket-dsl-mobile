package app.pocketdsl.archive

interface ArchiveExtractor {
    suspend fun listEntries(sourcePath: String): List<ArchiveEntry>
}
