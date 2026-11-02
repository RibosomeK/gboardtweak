package dev.ria.gboardtweak

import de.robv.android.xposed.XposedHelpers

/**
 * Tracks enough of the current IME session to avoid touching non-Chinese digit keyboards.
 *
 * Gboard's Chinese 9-key digit page and, for example, the Japanese digit page are both
 * rendered by Gboard's digit keyboard. The subtype language is the cheap discriminator.
 */
object ImeState {
    @Volatile
    private var service: Any? = null

    @Volatile
    private var languageTag: String? = null

    fun onServiceSeen(serviceInstance: Any?) {
        if (serviceInstance == null) {
            return
        }
        service = serviceInstance
        refreshNow()
    }

    fun refreshNow() {
        val serviceInstance = service ?: return
        try {
            val subtype = XposedHelpers.callMethod(
                serviceInstance,
                "getCurrentInputMethodSubtype"
            ) ?: run {
                languageTag = null
                DebugLog.w("current IME subtype is null")
                return
            }

            val tag = readLanguageTag(subtype)
            languageTag = tag
            DebugLog.i("IME subtype language=${tag ?: "<unknown>"}")
        } catch (t: Throwable) {
            languageTag = null
            DebugLog.w("failed to read current IME subtype", t)
        }
    }

    private fun readLanguageTag(subtype: Any): String? {
        try {
            val languageTag = XposedHelpers.callMethod(subtype, "getLanguageTag") as? String
            if (!languageTag.isNullOrEmpty()) {
                return languageTag
            }
        } catch (_: Throwable) {
            // Fall back to the deprecated getLocale() below.
        }
        return try {
            XposedHelpers.callMethod(subtype, "getLocale") as? String
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Unknown subtype information is treated as "allow": the caller still requires the
     * active keyboard to be the digit layout and the committed text to be exactly one
     * of the two target punctuation marks.
     */
    fun isChinese(): Boolean {
        val tag = languageTag ?: return true
        if (tag.isEmpty() || tag == "und") {
            return true
        }
        return tag.startsWith("zh", ignoreCase = true)
    }
}
