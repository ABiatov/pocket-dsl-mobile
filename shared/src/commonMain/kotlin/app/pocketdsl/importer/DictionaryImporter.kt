package app.pocketdsl.importer

interface DictionaryImporter {
    suspend fun import(sourcePath: String): ImportResult
}
