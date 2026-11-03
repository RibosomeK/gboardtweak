package dev.ria.gboardtweak

/**
 * Arithmetic operators assigned to the first four entries of the Chinese 9-key
 * left punctuation strip while the digit page is shown.
 *
 * The strip keys keep committing their original punctuation text even after their
 * key faces are relabeled, so [KeyLabelHooks] records the active label -> operator
 * mapping here and [InputConnectionHooks] consults it before applying the normal
 * punctuation transform.
 */
object MathSymbols {
    const val SLOT_COUNT = 4

    val displaySymbols = listOf("+", "-", "×", "÷")

    private val lock = Any()
    private val labelToSymbol = LinkedHashMap<String, String>()

    fun replaceMappings(mappings: Map<String, String>) {
        synchronized(lock) {
            labelToSymbol.clear()
            labelToSymbol.putAll(mappings)
        }
    }

    fun clear() {
        synchronized(lock) {
            labelToSymbol.clear()
        }
    }

    fun transform(text: CharSequence): CharSequence? {
        synchronized(lock) {
            return labelToSymbol[text.toString()]
        }
    }

    /**
     * Registers the label exactly as Gboard displays it, plus the common
     * half-width/full-width spelling of the same punctuation. Gboard normally
     * commits the same text it displays, but older/newer builds mix the widths.
     */
    fun variants(label: String): List<String> {
        val variants = linkedSetOf(label)
        if (label == "……") {
            variants.add("…")
        }
        val swapped = buildString(label.length) {
            for (ch in label) {
                append(WIDTH_SWAPS[ch] ?: ch)
            }
        }
        variants.add(swapped)
        return variants.toList()
    }

    private val WIDTH_SWAPS = mapOf(
        '？' to '?',
        '！' to '!',
        '～' to '~',
        '，' to ',',
        '。' to '.',
        '：' to ':',
        '；' to ';'
    )
}
