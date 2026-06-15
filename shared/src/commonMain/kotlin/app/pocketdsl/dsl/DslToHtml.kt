package app.pocketdsl.dsl

object DslToHtml {
    fun convert(raw: String): String =
        wrap(convertArticle(raw))

    private fun convertArticle(raw: String): String {
        val html = StringBuilder(raw.length)
        var index = 0

        while (index < raw.length) {
            val char = raw[index]
            if (char == '[') {
                val closeIndex = raw.indexOf(']', startIndex = index + 1)
                if (closeIndex != -1) {
                    val token = raw.substring(startIndex = index, endIndex = closeIndex + 1)
                    val replacement = token.toSafeHtmlTag()
                    if (replacement != null) {
                        html.append(replacement)
                    } else {
                        html.appendEscaped(token)
                    }
                    index = closeIndex + 1
                    continue
                }
            }

            when (char) {
                '\n' -> html.append("<br>")
                else -> html.appendEscaped(char)
            }
            index += 1
        }

        return html.toString()
    }

    private fun String.toSafeHtmlTag(): String? =
        when (this) {
            "[b]" -> "<b>"
            "[/b]" -> "</b>"
            "[i]" -> "<i>"
            "[/i]" -> "</i>"
            "[u]" -> "<u>"
            "[/u]" -> "</u>"
            "[sup]" -> "<sup>"
            "[/sup]" -> "</sup>"
            "[trn]" -> "<div class=\"trn\">"
            "[/trn]" -> "</div>"
            "[com]" -> "<span class=\"com\">"
            "[/com]" -> "</span>"
            "[c]" -> "<span class=\"label\">"
            "[/c]" -> "</span>"
            "[p]" -> "<span class=\"pos\">"
            "[/p]" -> "</span>"
            "[m1]" -> "<div class=\"m m1\">"
            "[m2]" -> "<div class=\"m m2\">"
            "[m3]" -> "<div class=\"m m3\">"
            "[/m]" -> "</div>"
            "[*]" -> "<span class=\"bullet\">\u2022 </span>"
            "[/*]" -> ""
            "[ex]" -> "<div class=\"ex\">"
            "[/ex]" -> "</div>"
            "[/lang]" -> "</span>"
            else -> if (isLangOpeningTag()) "<span class=\"lang\">" else null
        }

    private fun String.isLangOpeningTag(): Boolean =
        startsWith("[lang ") && endsWith("]") && length > "[lang ]".length

    private fun StringBuilder.appendEscaped(text: String) {
        text.forEach { char -> appendEscaped(char) }
    }

    private fun StringBuilder.appendEscaped(char: Char) {
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(char)
        }
    }

    private fun wrap(body: String): String =
        """
        <!doctype html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                body {
                    font-family: -apple-system, BlinkMacSystemFont, "Roboto", sans-serif;
                    font-size: 18px;
                    line-height: 1.45;
                    margin: 0;
                    padding: 12px;
                    color: #202124;
                    background: #ffffff;
                }

                .trn {
                    margin-top: 8px;
                }

                .m {
                    margin-top: 4px;
                }

                .m1 { margin-left: 0; }
                .m2 { margin-left: 18px; }
                .m3 { margin-left: 36px; }

                .com {
                    color: #666666;
                    font-style: italic;
                }

                .label,
                .pos {
                    color: #557a2f;
                    font-style: italic;
                }

                .ex {
                    margin-top: 4px;
                    color: #444444;
                }

                .lang {
                    font-weight: 500;
                }

                .bullet {
                    color: #555555;
                }
            </style>
        </head>
        <body>
        $body
        </body>
        </html>
        """.trimIndent()
}
