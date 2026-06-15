package app.pocketdsl.dsl

class DslParser {
    fun parse(text: String): Pair<DslDictionaryMeta, List<DslEntry>> {
        var name: String? = null
        var indexLanguage: String? = null
        var contentsLanguage: String? = null
        var currentHeadword: String? = null
        val currentArticleLines = mutableListOf<String>()
        val entries = mutableListOf<DslEntry>()

        fun flushCurrentEntry() {
            val headword = currentHeadword ?: return
            entries += DslEntry(
                headword = headword,
                articleRaw = currentArticleLines.joinToString(separator = "\n"),
            )
            currentHeadword = null
            currentArticleLines.clear()
        }

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.removeSuffix("\r")

            when {
                line.isBlank() -> {
                    if (currentHeadword != null) {
                        currentArticleLines += line
                    }
                }

                line.startsWith(" ") || line.startsWith("\t") -> {
                    if (currentHeadword != null) {
                        currentArticleLines += line
                    }
                }

                line.startsWith("#") -> {
                    val header = parseHeader(line)
                    when (header?.name) {
                        "NAME" -> name = header.value
                        "INDEX_LANGUAGE" -> indexLanguage = header.value
                        "CONTENTS_LANGUAGE" -> contentsLanguage = header.value
                    }
                }

                else -> {
                    flushCurrentEntry()
                    currentHeadword = line.trim()
                }
            }
        }

        flushCurrentEntry()

        return DslDictionaryMeta(
            name = name,
            indexLanguage = indexLanguage,
            contentsLanguage = contentsLanguage,
        ) to entries
    }

    fun parseStreaming(
        lines: Sequence<String>,
        maxArticleRawChars: Int? = null,
        onMetadata: (DslDictionaryMeta) -> Unit = {},
        onEntry: (DslEntry) -> Unit,
    ): DslDictionaryMeta {
        var name: String? = null
        var indexLanguage: String? = null
        var contentsLanguage: String? = null
        var currentHeadword: String? = null
        val currentArticleLines = mutableListOf<String>()
        var currentArticleCharCount = 0
        var currentArticleExceededLimit = false
        var metadataEmitted = false

        fun metadata(): DslDictionaryMeta =
            DslDictionaryMeta(
                name = name,
                indexLanguage = indexLanguage,
                contentsLanguage = contentsLanguage,
            )

        fun emitMetadataIfNeeded() {
            if (metadataEmitted) return
            onMetadata(metadata())
            metadataEmitted = true
        }

        fun resetArticle() {
            currentArticleLines.clear()
            currentArticleCharCount = 0
            currentArticleExceededLimit = false
        }

        fun flushCurrentEntry() {
            val headword = currentHeadword ?: return
            emitMetadataIfNeeded()
            onEntry(
                DslEntry(
                    headword = headword,
                    articleRaw = if (currentArticleExceededLimit) "" else currentArticleLines.joinToString(separator = "\n"),
                    articleRawExceededLimit = currentArticleExceededLimit,
                ),
            )
            currentHeadword = null
            resetArticle()
        }

        fun appendArticleLine(line: String) {
            if (currentArticleExceededLimit) return

            val separatorLength = if (currentArticleLines.isEmpty()) 0 else 1
            val nextCharCount = currentArticleCharCount + separatorLength + line.length
            if (maxArticleRawChars != null && nextCharCount > maxArticleRawChars) {
                currentArticleExceededLimit = true
                currentArticleLines.clear()
                return
            }

            currentArticleLines += line
            currentArticleCharCount = nextCharCount
        }

        lines.forEach { rawLine ->
            val line = rawLine.removeSuffix("\r")

            when {
                line.isBlank() -> {
                    if (currentHeadword != null) {
                        appendArticleLine(line)
                    }
                }

                line.startsWith(" ") || line.startsWith("\t") -> {
                    if (currentHeadword != null) {
                        appendArticleLine(line)
                    }
                }

                line.startsWith("#") -> {
                    val header = parseHeader(line)
                    when (header?.name) {
                        "NAME" -> name = header.value
                        "INDEX_LANGUAGE" -> indexLanguage = header.value
                        "CONTENTS_LANGUAGE" -> contentsLanguage = header.value
                    }
                }

                else -> {
                    flushCurrentEntry()
                    emitMetadataIfNeeded()
                    currentHeadword = line.trim()
                }
            }
        }

        flushCurrentEntry()
        emitMetadataIfNeeded()

        return metadata()
    }

    private fun parseHeader(line: String): Header? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("#")) return null

        val nameEnd = trimmed.indexOfFirst { it == ' ' || it == '\t' }
        if (nameEnd <= 1) return null

        val name = trimmed.substring(startIndex = 1, endIndex = nameEnd)
        val rawValue = trimmed.substring(startIndex = nameEnd).trim()
        if (rawValue.isEmpty()) return null

        return Header(name = name, value = rawValue.unquote())
    }

    private fun String.unquote(): String =
        if (length >= 2 && first() == '"' && last() == '"') {
            substring(startIndex = 1, endIndex = lastIndex)
        } else {
            this
        }

    private data class Header(
        val name: String,
        val value: String,
    )
}
