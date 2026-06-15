package app.pocketdsl.archive

data class ArchiveExtractionLimits(
    val maxCompressedSizeBytes: Long = 2L * 1024L * 1024L * 1024L,
    val maxTotalExtractedSizeBytes: Long = 5L * 1024L * 1024L * 1024L,
    val maxFileSizeBytes: Long = 1024L * 1024L * 1024L,
    val maxFileCount: Int = 10_000,
) {
    init {
        require(maxCompressedSizeBytes > 0) { "maxCompressedSizeBytes must be positive" }
        require(maxTotalExtractedSizeBytes > 0) { "maxTotalExtractedSizeBytes must be positive" }
        require(maxFileSizeBytes > 0) { "maxFileSizeBytes must be positive" }
        require(maxFileSizeBytes <= Int.MAX_VALUE) {
            "maxFileSizeBytes must be at most Int.MAX_VALUE for in-memory extraction"
        }
        require(maxFileCount > 0) { "maxFileCount must be positive" }
    }
}

class ArchiveExtractionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
