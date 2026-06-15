package app.pocketdsl.archive

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.SequenceInputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

class DslDzTextStream(
    private val limits: DslDzLimits = DslDzLimits(),
) {
    fun open(
        input: InputStream,
        compressedByteCount: Long? = null,
    ): DslDzStreamingText {
        if (compressedByteCount != null && compressedByteCount > limits.maxCompressedInputBytes) {
            throw DslDzExtractionException(
                "DSL.DZ compressed input exceeds limit: $compressedByteCount bytes > " +
                    "${limits.maxCompressedInputBytes} bytes",
            )
        }

        val gzip = try {
            GZIPInputStream(input)
        } catch (cause: IOException) {
            throw DslDzExtractionException("Invalid DSL.DZ gzip data", cause)
        }
        val limited = LimitedCountingInputStream(gzip, limits.maxDecompressedOutputBytes)
        val bom = readBom(limited)
        val readerInput = if (bom.remainingBytes.isEmpty()) {
            limited
        } else {
            SequenceInputStream(ByteArrayInputStream(bom.remainingBytes), limited)
        }
        val reader = BufferedReader(
            InputStreamReader(readerInput, bom.encoding.newReportingDecoder()),
            DEFAULT_BUFFER_SIZE,
        )

        val counters = DslDzStreamCounters()
        counters.decompressedByteCount = limited.bytesRead
        val lines = sequence {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    counters.decompressedByteCount = limited.bytesRead
                    counters.decompressedCharCount += line.length + 1L
                    if (counters.decompressedCharCount > limits.maxDecompressedOutputBytes) {
                        throw DslDzExtractionException(
                            "DSL.DZ decompressed text exceeds character limit: " +
                                "${counters.decompressedCharCount} chars > ${limits.maxDecompressedOutputBytes} chars",
                        )
                    }
                    yield(line)
                }
                counters.decompressedByteCount = limited.bytesRead
            } catch (cause: CharacterCodingException) {
                throw DslTextDecodingException("DSL text is not valid UTF-8 or UTF-16 with BOM", cause)
            } catch (cause: IOException) {
                throw DslDzExtractionException("Invalid DSL.DZ gzip data", cause)
            }
        }

        return DslDzStreamingText(
            lines = lines,
            encoding = bom.encoding,
            counters = counters,
            closeAction = {
                reader.close()
            },
        )
    }

    private fun readBom(input: InputStream): BomDetection {
        val prefix = ByteArray(3)
        var count = 0
        while (count < prefix.size) {
            val read = input.read(prefix, count, prefix.size - count)
            if (read == -1) break
            count += read
        }

        return when {
            count >= 2 && prefix[0] == 0xFF.toByte() && prefix[1] == 0xFE.toByte() -> {
                BomDetection(
                    encoding = DslTextEncoding.UTF_16_LE,
                    remainingBytes = prefix.copyOfRange(2, count),
                )
            }
            count >= 2 && prefix[0] == 0xFE.toByte() && prefix[1] == 0xFF.toByte() -> {
                BomDetection(
                    encoding = DslTextEncoding.UTF_16_BE,
                    remainingBytes = prefix.copyOfRange(2, count),
                )
            }
            count >= 3 &&
                prefix[0] == 0xEF.toByte() &&
                prefix[1] == 0xBB.toByte() &&
                prefix[2] == 0xBF.toByte() -> {
                BomDetection(
                    encoding = DslTextEncoding.UTF_8_WITH_BOM,
                    remainingBytes = ByteArray(0),
                )
            }
            else -> {
                BomDetection(
                    encoding = DslTextEncoding.UTF_8,
                    remainingBytes = prefix.copyOfRange(0, count),
                )
            }
        }
    }

    private fun DslTextEncoding.newReportingDecoder() =
        when (this) {
            DslTextEncoding.UTF_8, DslTextEncoding.UTF_8_WITH_BOM -> StandardCharsets.UTF_8
            DslTextEncoding.UTF_16_LE -> StandardCharsets.UTF_16LE
            DslTextEncoding.UTF_16_BE -> StandardCharsets.UTF_16BE
        }.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

    private data class BomDetection(
        val encoding: DslTextEncoding,
        val remainingBytes: ByteArray,
    )
}

class DslDzStreamingText(
    val lines: Sequence<String>,
    val encoding: DslTextEncoding,
    val counters: DslDzStreamCounters,
    private val closeAction: () -> Unit,
) : Closeable {
    override fun close() {
        closeAction()
    }
}

class DslDzStreamCounters {
    var decompressedByteCount: Long = 0
    var decompressedCharCount: Long = 0
}

private class LimitedCountingInputStream(
    input: InputStream,
    private val limitBytes: Long,
) : FilterInputStream(input) {
    var bytesRead: Long = 0
        private set

    override fun read(): Int {
        val value = super.read()
        if (value != -1) {
            increment(1)
        }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(buffer, offset, length)
        if (read > 0) {
            increment(read)
        }
        return read
    }

    private fun increment(count: Int) {
        bytesRead += count.toLong()
        if (bytesRead > limitBytes) {
            throw DslDzExtractionException(
                "DSL.DZ decompressed output exceeds limit: $bytesRead bytes > $limitBytes bytes",
            )
        }
    }
}
