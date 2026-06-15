package app.pocketdsl.dsl

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DslToHtmlTest {
    @Test
    fun convertsBoldTag() {
        assertHtmlContains(DslToHtml.convert("[b]word[/b]"), "<b>word</b>")
    }

    @Test
    fun convertsItalicTag() {
        assertHtmlContains(DslToHtml.convert("[i]word[/i]"), "<i>word</i>")
    }

    @Test
    fun convertsUnderlineTag() {
        assertHtmlContains(DslToHtml.convert("[u]word[/u]"), "<u>word</u>")
    }

    @Test
    fun convertsSupTag() {
        assertHtmlContains(DslToHtml.convert("x[sup]2[/sup]"), "x<sup>2</sup>")
    }

    @Test
    fun convertsTranslationBlock() {
        assertHtmlContains(
            DslToHtml.convert("[trn]translation[/trn]"),
            "<div class=\"trn\">translation</div>",
        )
    }

    @Test
    fun convertsCommentLabelAndPartOfSpeechTags() {
        val html = DslToHtml.convert("[com]note[/com] [c]label[/c] [p]noun[/p]")

        assertHtmlContains(html, "<span class=\"com\">note</span>")
        assertHtmlContains(html, "<span class=\"label\">label</span>")
        assertHtmlContains(html, "<span class=\"pos\">noun</span>")
    }

    @Test
    fun convertsMIndentationTags() {
        val html = DslToHtml.convert("[m1]one[/m]\n[m2]two[/m]\n[m3]three[/m]")

        assertHtmlContains(html, "<div class=\"m m1\">one</div>")
        assertHtmlContains(html, "<div class=\"m m2\">two</div>")
        assertHtmlContains(html, "<div class=\"m m3\">three</div>")
        assertHtmlContains(html, "<br>")
    }

    @Test
    fun convertsExampleBlock() {
        assertHtmlContains(
            DslToHtml.convert("[ex]example[/ex]"),
            "<div class=\"ex\">example</div>",
        )
    }

    @Test
    fun convertsLangTagWithNameAttribute() {
        assertHtmlContains(
            DslToHtml.convert("[lang name=\"English\"]text[/lang]"),
            "<span class=\"lang\">text</span>",
        )
    }

    @Test
    fun convertsLangTagWithIdAttribute() {
        assertHtmlContains(
            DslToHtml.convert("[lang id=1]text[/lang]"),
            "<span class=\"lang\">text</span>",
        )
    }

    @Test
    fun convertsBulletTag() {
        assertHtmlContains(
            DslToHtml.convert("[*]item[/*]"),
            "<span class=\"bullet\">• </span>item",
        )
    }

    @Test
    fun escapesRawHtml() {
        val html = DslToHtml.convert("<script>alert(1)</script>")

        assertHtmlContains(html, "&lt;script&gt;alert(1)&lt;/script&gt;")
        assertHtmlDoesNotContain(html, "<script>")
        assertHtmlDoesNotContain(html, "</script>")
    }

    @Test
    fun escapesAmpersands() {
        assertHtmlContains(DslToHtml.convert("Tom & Jerry"), "Tom &amp; Jerry")
    }

    @Test
    fun preservesUnknownTagsAsEscapedText() {
        assertHtmlContains(
            DslToHtml.convert("[unknown]<unsafe>[/unknown]"),
            "[unknown]&lt;unsafe&gt;[/unknown]",
        )
    }

    @Test
    fun generatedDocumentContainsRequiredStructureAndStaticCss() {
        val html = DslToHtml.convert("article")

        assertHtmlContains(html, "<!doctype html>")
        assertHtmlContains(html, "<html>")
        assertHtmlContains(html, "<head>")
        assertHtmlContains(html, "<meta charset=\"utf-8\">")
        assertHtmlContains(html, "<meta name=\"viewport\"")
        assertHtmlContains(html, "<style>")
        assertHtmlContains(html, ".trn")
        assertHtmlContains(html, "<body>")
        assertHtmlContains(html, "</body>")
        assertHtmlContains(html, "</html>")
    }

    private fun assertHtmlContains(html: String, expected: String) {
        assertTrue(
            actual = html.contains(expected),
            message = "Expected HTML to contain <$expected>, but was:\n$html",
        )
    }

    private fun assertHtmlDoesNotContain(html: String, unexpected: String) {
        assertFalse(
            actual = html.contains(unexpected),
            message = "Expected HTML not to contain <$unexpected>, but was:\n$html",
        )
    }
}
