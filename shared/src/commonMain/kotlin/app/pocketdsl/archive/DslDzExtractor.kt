package app.pocketdsl.archive

/**
 * Sequential DSL.DZ decompressor.
 *
 * Dictzip files are gzip-compatible streams with extra metadata for random access.
 * Stage 6 intentionally ignores that random-access metadata and performs full
 * sequential decompression into memory so the existing plain DSL importer can be
 * reused. Extracted text may be UTF-8 or UTF-16 with a byte order mark.
 */
expect class DslDzExtractor(limits: DslDzLimits = DslDzLimits()) {
    fun extractToText(bytes: ByteArray): String
}

data class DslDzLimits(
    val maxCompressedInputBytes: Long = 512L * 1024L * 1024L,
    val maxDecompressedOutputBytes: Long = 1024L * 1024L * 1024L,
) {
    init {
        require(maxCompressedInputBytes > 0) { "maxCompressedInputBytes must be positive" }
        require(maxDecompressedOutputBytes > 0) { "maxDecompressedOutputBytes must be positive" }
        require(maxDecompressedOutputBytes <= Int.MAX_VALUE) {
            "maxDecompressedOutputBytes must be at most Int.MAX_VALUE for in-memory extraction"
        }
    }
}

class DslDzExtractionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
