package app.pocketdsl.dsl

data class DslEntry(
    val headword: String,
    val articleRaw: String,
    val articleRawExceededLimit: Boolean = false,
)
