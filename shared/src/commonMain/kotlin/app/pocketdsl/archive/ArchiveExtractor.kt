package app.pocketdsl.archive

interface ArchiveExtractor {
    fun extract(bytes: ByteArray): List<ArchiveEntry>
}
