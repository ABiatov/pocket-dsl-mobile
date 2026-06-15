package app.pocketdsl.dsl

data class DslTag(
    val name: String,
    val attributes: Map<String, String> = emptyMap(),
)
