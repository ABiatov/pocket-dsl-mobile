package app.pocketdsl.archive

data class ArchiveEntry(
    val path: String,
    val bytes: ByteArray,
) {
    val sizeBytes: Long
        get() = bytes.size.toLong()
}
