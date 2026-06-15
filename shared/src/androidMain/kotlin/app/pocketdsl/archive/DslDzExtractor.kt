package app.pocketdsl.archive

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

actual class DslDzExtractor actual constructor(
    private val limits: DslDzLimits,
) {
    actual fun extract(bytes: ByteArray): DslDzExtractionResult {
        ensureCompressedSize(bytes)

        val decompressed = try {
            decompress(bytes)
        } catch (cause: IOException) {
            throw DslDzExtractionException("Invalid DSL.DZ gzip data", cause)
        }

        val decoded = decodeDslText(decompressed)
        return DslDzExtractionResult(
            text = decoded.text,
            compressedByteCount = bytes.size.toLong(),
            decompressedByteCount = decompressed.size.toLong(),
            encoding = decoded.encoding,
        )
    }

    actual fun extractToText(bytes: ByteArray): String =
        extract(bytes).text

    private fun ensureCompressedSize(bytes: ByteArray) {
        if (bytes.size.toLong() > limits.maxCompressedInputBytes) {
            throw DslDzExtractionException(
                "DSL.DZ compressed input exceeds limit: ${bytes.size} bytes > " +
                    "${limits.maxCompressedInputBytes} bytes",
            )
        }
    }

    private fun decompress(bytes: ByteArray): ByteArray {
        ByteArrayInputStream(bytes).use { input ->
            GZIPInputStream(input).use { gzip ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var totalBytes = 0L

                while (true) {
                    val read = gzip.read(buffer)
                    if (read == -1) break

                    totalBytes += read.toLong()
                    if (totalBytes > limits.maxDecompressedOutputBytes) {
                        throw DslDzExtractionException(
                            "DSL.DZ decompressed output exceeds limit: $totalBytes bytes > " +
                                "${limits.maxDecompressedOutputBytes} bytes",
                        )
                    }

                    output.write(buffer, 0, read)
                }

                return output.toByteArray()
            }
        }
    }

    private fun decodeDslText(bytes: ByteArray): DslDecodedText =
        try {
            DslTextDecoder.decodeWithInfo(bytes)
        } catch (cause: DslTextDecodingException) {
            throw DslDzExtractionException("Decompressed DSL text is not valid UTF-8 or UTF-16 with BOM", cause)
        }
}
