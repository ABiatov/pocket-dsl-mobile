package app.pocketdsl.dsl

import kotlin.test.Test
import kotlin.test.assertEquals

class DslParserTest {
    private val parser = DslParser()

    @Test
    fun parsesQuotedNameMetadataWithSpaces() {
        val (meta, entries) = parser.parse(
            """
            |#NAME "Apresyan (En-Ru)"
            |#INDEX_LANGUAGE "English"
            |#CONTENTS_LANGUAGE "Russian"
            |
            |apple
            | [trn]яблоко[/trn]
            """.trimMargin(),
        )

        assertEquals("Apresyan (En-Ru)", meta.name)
        assertEquals("English", meta.indexLanguage)
        assertEquals("Russian", meta.contentsLanguage)
        assertEquals(1, entries.size)
    }

    @Test
    fun parsesQuotedNameMetadataWithTabs() {
        val (meta, entries) = parser.parse(
            """
            |#NAME	"Smirnitsky (Ru-En)"
            |#INDEX_LANGUAGE	"Russian"
            |#CONTENTS_LANGUAGE	"English"
            |
            |абажур
            |	[trn]lampshade[/trn]
            """.trimMargin(),
        )

        assertEquals("Smirnitsky (Ru-En)", meta.name)
        assertEquals("Russian", meta.indexLanguage)
        assertEquals("English", meta.contentsLanguage)
        assertEquals(1, entries.size)
    }

    @Test
    fun parsesEnRuArticleWithSpaceIndentedLines() {
        val (_, entries) = parser.parse(
            """
            |test
            | [trn]тест[/trn]
            | [com]artificial example[/com]
            """.trimMargin(),
        )

        assertEquals(
            DslEntry(
                headword = "test",
                articleRaw = " [trn]тест[/trn]\n [com]artificial example[/com]",
            ),
            entries.single(),
        )
    }

    @Test
    fun parsesRuEnArticleWithTabIndentedLines() {
        val (_, entries) = parser.parse(
            """
            |тест
            |	[trn]test[/trn]
            |	[com]synthetic entry[/com]
            """.trimMargin(),
        )

        assertEquals(
            DslEntry(
                headword = "тест",
                articleRaw = "\t[trn]test[/trn]\n\t[com]synthetic entry[/com]",
            ),
            entries.single(),
        )
    }

    @Test
    fun parsesApostropheHeadwords() {
        val (_, entries) = parser.parse(
            """
            |'cello
            | [trn]synthetic cello entry[/trn]
            """.trimMargin(),
        )

        assertEquals("'cello", entries.single().headword)
    }

    @Test
    fun parsesCyrillicHeadwords() {
        val (_, entries) = parser.parse(
            """
            |абажур
            |	[trn]artificial lampshade entry[/trn]
            """.trimMargin(),
        )

        assertEquals("абажур", entries.single().headword)
    }

    @Test
    fun parsesHyphenatedHeadwords() {
        val (_, entries) = parser.parse(
            """
            |а-
            |	[trn]synthetic prefix entry[/trn]
            """.trimMargin(),
        )

        assertEquals("а-", entries.single().headword)
    }

    @Test
    fun parsesMultipleEntriesInOneFile() {
        val (_, entries) = parser.parse(
            """
            |alpha
            | [trn]first[/trn]
            |beta
            | [trn]second[/trn]
            |gamma
            | [trn]third[/trn]
            """.trimMargin(),
        )

        assertEquals(
            listOf(
                DslEntry("alpha", " [trn]first[/trn]"),
                DslEntry("beta", " [trn]second[/trn]"),
                DslEntry("gamma", " [trn]third[/trn]"),
            ),
            entries,
        )
    }

    @Test
    fun flushesFinalEntryAtEofWithoutTrailingNewline() {
        val (_, entries) = parser.parse("omega\n [trn]last[/trn]")

        assertEquals(
            DslEntry("omega", " [trn]last[/trn]"),
            entries.single(),
        )
    }

    @Test
    fun ignoresMalformedAndUnknownHeaders() {
        val (_, entries) = parser.parse(
            """
            |#UNKNOWN "ignored"
            |#BROKEN
            |word
            | [bad]raw unknown tag is preserved[/bad]
            """.trimMargin(),
        )

        assertEquals(
            DslEntry("word", " [bad]raw unknown tag is preserved[/bad]"),
            entries.single(),
        )
    }
}
