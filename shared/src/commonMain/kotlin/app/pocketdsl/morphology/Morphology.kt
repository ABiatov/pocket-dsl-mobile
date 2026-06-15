package app.pocketdsl.morphology

interface Morphology {
    fun lookupBaseForms(word: String): List<String>
}
