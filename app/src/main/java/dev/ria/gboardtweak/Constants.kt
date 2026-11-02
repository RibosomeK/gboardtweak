package dev.ria.gboardtweak

object Constants {
    const val TAG = "GboardTweak"

    const val TARGET_PACKAGE = "com.google.android.inputmethod.latin"

    // Stable, reflection-visible classes in Gboard 18.5.4.
    const val KEYBOARD_CLASS =
        "com.google.android.libraries.inputmethod.keyboard.impl.Keyboard"
    const val DIGITS_KEYBOARD_CLASS =
        "com.google.android.apps.inputmethod.latin.keyboard.LatinDigitsKeyboard"

    // Framework class. The actual InputConnection implementation is discovered at runtime,
    // so the commit hook does not depend on Gboard's obfuscated class names.
    const val INPUT_METHOD_SERVICE_CLASS = "android.inputmethodservice.InputMethodService"

    // Version-specific fallback for Gboard 18.5.4.993608717:
    // aekp.q(CharSequence, int) is Gboard's InputConnectionWrapper.commitText, and
    // aemg.b(aemw, CharSequence, int) is the internal queueing helper.
    const val GBOARD_COMMIT_WRAPPER_CLASS = "aekp"
    const val GBOARD_COMMIT_QUEUE_CLASS = "aemg"

    // Logs every target punctuation commit while developing. Set to false for release builds.
    const val DEBUG = true
}
