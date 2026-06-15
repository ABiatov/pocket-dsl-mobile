package app.pocketdsl.archive

class TarBz2Extractor : ArchiveExtractor {
    override suspend fun listEntries(sourcePath: String): List<ArchiveEntry> =
        TODO("tar.bz2 archive import is not implemented yet")
}
