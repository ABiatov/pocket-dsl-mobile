package app.pocketdsl.archive

internal object DslTextDecoder {
    fun decode(bytes: ByteArray): String =
        when {
            bytes.startsWith(UTF_16_LE_BOM) -> decodeUtf16(bytes, offset = UTF_16_LE_BOM.size, littleEndian = true)
            bytes.startsWith(UTF_16_BE_BOM) -> decodeUtf16(bytes, offset = UTF_16_BE_BOM.size, littleEndian = false)
            bytes.startsWith(UTF_8_BOM) -> decodeUtf8(bytes, offset = UTF_8_BOM.size)
            else -> decodeUtf8(bytes, offset = 0)
        }

    private fun decodeUtf8(bytes: ByteArray, offset: Int): String =
        try {
            bytes.decodeToString(startIndex = offset, throwOnInvalidSequence = true)
        } catch (cause: Throwable) {
            throw DslTextDecodingException("DSL text is not valid UTF-8 or UTF-16 with BOM", cause)
        }

    private fun decodeUtf16(bytes: ByteArray, offset: Int, littleEndian: Boolean): String {
        val contentByteCount = bytes.size - offset
        if (contentByteCount % 2 != 0) {
            throw DslTextDecodingException("UTF-16 DSL text has an odd byte length")
        }

        val chars = CharArray(contentByteCount / 2)
        var byteIndex = offset
        var charIndex = 0
        while (byteIndex < bytes.size) {
            val first = bytes[byteIndex].toInt() and 0xFF
            val second = bytes[byteIndex + 1].toInt() and 0xFF
            val codeUnit = if (littleEndian) {
                first or (second shl 8)
            } else {
                (first shl 8) or second
            }
            chars[charIndex] = codeUnit.toChar()
            byteIndex += 2
            charIndex += 1
        }
        return chars.concatToString()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

    private val UTF_8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF_16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF_16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
}

class DslTextDecodingException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
