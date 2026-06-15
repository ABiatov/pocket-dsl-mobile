package app.pocketdsl.search

import kotlin.test.Test
import kotlin.test.assertEquals

class WordNormalizerTest {
    @Test
    fun trimsLowercasesAndReplacesRussianYo() {
        assertEquals(
            "еж",
            WordNormalizer.normalize("  ЁЖ  "),
        )
    }

    @Test
    fun lowercasesLatinWords() {
        assertEquals(
            "cello",
            WordNormalizer.normalize("  CELLO  "),
        )
    }
}
