package app.pocketdsl.archive

object DslTextDecoder {
    fun decode(bytes: ByteArray): String =
        decodeWithInfo(bytes).text

    fun decodeWithInfo(bytes: ByteArray): DslDecodedText =
        when {
            bytes.startsWith(UTF_16_LE_BOM) -> DslDecodedText(
                text = decodeUtf16(bytes, offset = UTF_16_LE_BOM.size, littleEndian = true),
                encoding = DslTextEncoding.UTF_16_LE,
            )

            bytes.startsWith(UTF_16_BE_BOM) -> DslDecodedText(
                text = decodeUtf16(bytes, offset = UTF_16_BE_BOM.size, littleEndian = false),
                encoding = DslTextEncoding.UTF_16_BE,
            )

            bytes.startsWith(UTF_8_BOM) -> DslDecodedText(
                text = decodeUtf8(bytes, offset = UTF_8_BOM.size),
                encoding = DslTextEncoding.UTF_8_WITH_BOM,
            )

            else -> DslDecodedText(
                text = decodeUtf8(bytes, offset = 0),
                encoding = DslTextEncoding.UTF_8,
            )
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

data class DslDecodedText(
    val text: String,
    val encoding: DslTextEncoding,
)

enum class DslTextEncoding(val displayName: String) {
    UTF_8("UTF-8"),
    UTF_8_WITH_BOM("UTF-8 BOM"),
    UTF_16_LE("UTF-16LE BOM"),
    UTF_16_BE("UTF-16BE BOM"),
}

class DslTextDecodingException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
