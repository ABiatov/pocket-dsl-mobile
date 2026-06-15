package app.pocketdsl.archive

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

actual class DslDzExtractor actual constructor(
    private val limits: DslDzLimits,
) {
    actual fun extractToText(bytes: ByteArray): String {
        ensureCompressedSize(bytes)

        val decompressed = try {
            decompress(bytes)
        } catch (cause: IOException) {
            throw DslDzExtractionException("Invalid DSL.DZ gzip data", cause)
        }

        return decodeUtf8(decompressed)
    }

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

    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (cause: CharacterCodingException) {
            throw DslDzExtractionException("Decompressed DSL text is not valid UTF-8", cause)
        }
    }
}
