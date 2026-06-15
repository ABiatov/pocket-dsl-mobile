package app.pocketdsl.search

object WordNormalizer {
    fun normalize(value: String): String =
        value.trim().lowercase().replace(oldChar = 'ё', newChar = 'е')
}
