package dev.ria.gboardtweak

import java.util.Locale

/**
 * The active keyboard in the Chinese 9-key IME reports a localized name such as
 * "简体中文 (9 键)". Gboard's keyboard object does not expose the IME subtype language,
 * and InputMethodService has no public getCurrentInputMethodSubtype() on Android 16.
 * The keyboard name is therefore the most direct available language indicator.
 *
 * Conversion is additionally gated on the number-mode keyboard class and the digit
 * mode state bit, so this is a defense-in-depth check rather than the only guard.
 */
object ImeState {
    private val CHINESE_MARKERS = arrayOf(
        "中文",
        "简体",
        "简中",
        "繁体",
        "繁體",
        "繁中",
        "汉语",
        "漢語",
        "粵語",
        "粤语",
        "拼音",
        "注音",
        "笔画",
        "筆畫",
        "仓颉",
        "倉頡",
        "速成",
        "五笔",
        "五筆",
    )

    private val CHINESE_LATIN_MARKERS = arrayOf(
        "chinese",
        "pinyin",
        "cantonese",
        "zhuyin",
    )

    fun isChinese(): Boolean {
        val name = KeyboardStateTracker.activeName() ?: return false
        if (CHINESE_MARKERS.any { name.contains(it) }) {
            return true
        }
        val lower = name.lowercase(Locale.ROOT)
        return CHINESE_LATIN_MARKERS.any { lower.contains(it) }
    }
}
