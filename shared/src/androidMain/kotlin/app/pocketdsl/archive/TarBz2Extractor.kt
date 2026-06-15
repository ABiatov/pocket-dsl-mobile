package app.pocketdsl.archive

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Paths
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

class TarBz2Extractor(
    private val limits: ArchiveExtractionLimits = ArchiveExtractionLimits(),
) : ArchiveExtractor {
    override fun extract(bytes: ByteArray): List<ArchiveEntry> {
        ensureCompressedSize(bytes)

        try {
            ByteArrayInputStream(bytes).use { byteInput ->
                BZip2CompressorInputStream(byteInput, true).use { bzipInput ->
                    TarArchiveInputStream(bzipInput).use { tarInput ->
                        return readTarEntries(tarInput)
                    }
                }
            }
        } catch (cause: ArchiveExtractionException) {
            throw cause
        } catch (cause: IOException) {
            throw ArchiveExtractionException("Invalid tar.bz2 archive", cause)
        } catch (cause: RuntimeException) {
            throw ArchiveExtractionException("Invalid tar.bz2 archive", cause)
        }
    }

    private fun ensureCompressedSize(bytes: ByteArray) {
        if (bytes.size.toLong() > limits.maxCompressedSizeBytes) {
            throw ArchiveExtractionException(
                "Archive compressed input exceeds limit: ${bytes.size} bytes > " +
                    "${limits.maxCompressedSizeBytes} bytes",
            )
        }
    }

    private fun readTarEntries(tarInput: TarArchiveInputStream): List<ArchiveEntry> {
        val extractedEntries = mutableListOf<ArchiveEntry>()
        var totalExtractedSize = 0L

        while (true) {
            val tarEntry = nextEntry(tarInput) ?: break
            val safePath = normalizeSafeRelativePath(tarEntry.name)

            when {
                tarEntry.isSymbolicLink -> {
                    throw ArchiveExtractionException("Archive entry '$safePath' is a symlink")
                }

                tarEntry.isLink -> {
                    throw ArchiveExtractionException("Archive entry '$safePath' is a hardlink")
                }

                tarEntry.isDirectory -> {
                    continue
                }

                tarEntry.isFile -> {
                    if (extractedEntries.size + 1 > limits.maxFileCount) {
                        throw ArchiveExtractionException(
                            "Archive file count exceeds limit: ${extractedEntries.size + 1} files > " +
                                "${limits.maxFileCount} files",
                        )
                    }

                    validateDeclaredFileSize(tarEntry, safePath, totalExtractedSize)
                    val entryBytes = readRegularFile(tarInput, safePath, totalExtractedSize)
                    totalExtractedSize = checkedAdd(totalExtractedSize, entryBytes.size.toLong()) {
                        "Archive total extracted size exceeds limit: more than " +
                            "${limits.maxTotalExtractedSizeBytes} bytes"
                    }
                    extractedEntries += ArchiveEntry(path = safePath, bytes = entryBytes)
                }

                else -> {
                    throw ArchiveExtractionException("Archive entry '$safePath' has an unsupported type")
                }
            }
        }

        return extractedEntries
    }

    private fun nextEntry(tarInput: TarArchiveInputStream): TarArchiveEntry? =
        try {
            tarInput.nextEntry
        } catch (cause: IOException) {
            throw ArchiveExtractionException("Invalid tar archive entry", cause)
        }

    private fun validateDeclaredFileSize(
        tarEntry: TarArchiveEntry,
        safePath: String,
        totalExtractedSize: Long,
    ) {
        val declaredSize = tarEntry.size
        if (declaredSize < 0) {
            throw ArchiveExtractionException("Archive entry '$safePath' has an invalid size")
        }

        if (declaredSize > limits.maxFileSizeBytes) {
            throw ArchiveExtractionException(
                "Archive entry '$safePath' exceeds file size limit: $declaredSize bytes > " +
                    "${limits.maxFileSizeBytes} bytes",
            )
        }

        if (declaredSize > limits.maxTotalExtractedSizeBytes - totalExtractedSize) {
            throw ArchiveExtractionException(
                "Archive total extracted size exceeds limit: " +
                    "${totalExtractedSize + declaredSize} bytes > " +
                    "${limits.maxTotalExtractedSizeBytes} bytes",
            )
        }
    }

    private fun readRegularFile(
        tarInput: TarArchiveInputStream,
        safePath: String,
        totalExtractedSizeBeforeEntry: Long,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var fileSize = 0L

        while (true) {
            val read = try {
                tarInput.read(buffer)
            } catch (cause: IOException) {
                throw ArchiveExtractionException("Invalid data for archive entry '$safePath'", cause)
            }

            if (read == -1) break

            fileSize = checkedAdd(fileSize, read.toLong()) {
                "Archive entry '$safePath' exceeds file size limit"
            }

            if (fileSize > limits.maxFileSizeBytes) {
                throw ArchiveExtractionException(
                    "Archive entry '$safePath' exceeds file size limit: $fileSize bytes > " +
                        "${limits.maxFileSizeBytes} bytes",
                )
            }

            if (fileSize > limits.maxTotalExtractedSizeBytes - totalExtractedSizeBeforeEntry) {
                throw ArchiveExtractionException(
                    "Archive total extracted size exceeds limit: " +
                        "${totalExtractedSizeBeforeEntry + fileSize} bytes > " +
                        "${limits.maxTotalExtractedSizeBytes} bytes",
                )
            }

            output.write(buffer, 0, read)
        }

        return output.toByteArray()
    }

    private fun normalizeSafeRelativePath(rawPath: String): String {
        val path = rawPath.replace('\\', '/')

        if (path.isBlank()) {
            throw ArchiveExtractionException("Archive entry has an empty path")
        }

        if (path.startsWith("/") || path.startsWith("//") || WINDOWS_DRIVE_PATH.matches(path)) {
            throw ArchiveExtractionException("Archive entry '$rawPath' uses an absolute path")
        }

        val segments = path.split('/')
            .filter { it.isNotEmpty() && it != "." }

        if (segments.isEmpty()) {
            throw ArchiveExtractionException("Archive entry '$rawPath' does not resolve to a file path")
        }

        if (segments.any { it == ".." }) {
            throw ArchiveExtractionException("Archive entry '$rawPath' contains path traversal")
        }

        val normalized = segments.joinToString("/")
        val root = Paths.get("archive-root").normalize()
        val outputPath = root.resolve(normalized).normalize()
        if (!outputPath.startsWith(root)) {
            throw ArchiveExtractionException("Archive entry '$rawPath' escapes the extraction root")
        }

        return normalized
    }

    private fun checkedAdd(left: Long, right: Long, message: () -> String): Long {
        if (left > Long.MAX_VALUE - right) {
            throw ArchiveExtractionException(message())
        }
        return left + right
    }

    private companion object {
        val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:($|/).*")
    }
}
