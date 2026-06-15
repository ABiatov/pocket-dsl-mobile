package app.pocketdsl.archive

actual class DslDzExtractor actual constructor(
    @Suppress("UNUSED_PARAMETER")
    limits: DslDzLimits,
) {
    actual fun extract(bytes: ByteArray): DslDzExtractionResult {
        throw DslDzExtractionException("DSL.DZ extraction is not implemented for iOS in Stage 6")
    }

    actual fun extractToText(bytes: ByteArray): String {
        return extract(bytes).text
    }
}
