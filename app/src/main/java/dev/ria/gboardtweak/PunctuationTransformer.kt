package dev.ria.gboardtweak

object PunctuationTransformer {
    private const val FULLWIDTH_COMMA = '，' // U+FF0C
    private const val FULLWIDTH_PERIOD = '。' // U+3002
    private const val ASCII_COMMA = ","
    private const val ASCII_PERIOD = "."

    fun isTarget(text: CharSequence?): Boolean {
        if (text == null || text.length != 1) {
            return false
        }
        return text[0] == FULLWIDTH_COMMA || text[0] == FULLWIDTH_PERIOD
    }

    fun transform(text: CharSequence): CharSequence {
        if (text.length != 1) {
            return text
        }
        return when (text[0]) {
            FULLWIDTH_COMMA -> ASCII_COMMA
            FULLWIDTH_PERIOD -> ASCII_PERIOD
            else -> text
        }
    }
}
