package dev.ria.gboardtweak

object Constants {
    const val TAG = "GboardTweak"

    const val TARGET_PACKAGE = "com.google.android.inputmethod.latin"

    // Named, reflection-visible classes in Gboard 18.5.4.
    const val KEYBOARD_CLASS =
        "com.google.android.libraries.inputmethod.keyboard.impl.Keyboard"
    const val NUMBER_MODE_KEYBOARD_CLASS =
        "com.google.android.apps.inputmethod.latin.keyboard.LatinKeyboardWithNumberMode"
    const val SOFT_KEY_VIEW_CLASS =
        "com.google.android.libraries.inputmethod.widgets.SoftKeyView"

    // Observed keyboard state on Gboard 18.5.4:
    //   letter page 0x4201808400
    //   digit page  0x64201808400  (adds CAPS | CAPS_LOCK_MASK)
    // The digit page reuses the caps-lock bits, so both must be present.
    const val DIGIT_MODE_STATE_MASK = 0x60000000000L

    // Framework class. The concrete InputConnection implementation is discovered at
    // runtime, so the primary commit hook does not depend on Gboard's obfuscated names.
    const val INPUT_METHOD_SERVICE_CLASS = "android.inputmethodservice.InputMethodService"

    // Gboard 18.5.4 InputConnectionWrapper.commitText. This is the path that has been
    // verified on-device and also runs before the framework InputConnection call.
    const val GBOARD_COMMIT_WRAPPER_CLASS = "aekp"

    // Verbose logging for development. Keep false for normal use.
    const val DEBUG = false
}
