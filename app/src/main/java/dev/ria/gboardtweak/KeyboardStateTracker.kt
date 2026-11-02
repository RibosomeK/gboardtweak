package dev.ria.gboardtweak

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Gboard's Chinese 9-key numeric page is a separate keyboard definition rendered by
 * com.google.android.apps.inputmethod.latin.keyboard.LatinDigitsKeyboard (res/rb7.xml
 * in 18.5.4). Tracking which Keyboard object is currently activated is therefore a
 * much simpler and more reliable state signal than reverse-engineering the up-arrow key.
 */
object KeyboardStateTracker {
    @Volatile
    private var activeKeyboard: Any? = null

    @Volatile
    private var digitKeyboardActive: Boolean = false

    @Volatile
    private var activeKeyboardClass: String? = null

    @Volatile
    private var activeKeyboardName: String? = null

    fun install(classLoader: ClassLoader) {
        val keyboardClass = XposedHelpers.findClassIfExists(
            Constants.KEYBOARD_CLASS,
            classLoader
        )

        if (keyboardClass == null) {
            DebugLog.e("Keyboard class not found: ${Constants.KEYBOARD_CLASS}")
            return
        }

        XposedBridge.hookAllMethods(keyboardClass, "e", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                onActivate(param.thisObject)
            }
        })

        XposedBridge.hookAllMethods(keyboardClass, "f", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                onDeactivate(param.thisObject)
            }
        })

        DebugLog.i("keyboard lifecycle hooks installed on ${keyboardClass.name}")
    }

    private fun onActivate(keyboard: Any?) {
        if (keyboard == null) {
            return
        }

        val clazz = keyboard.javaClass.name
        val isDigit = clazz == Constants.DIGITS_KEYBOARD_CLASS
        val name = keyboardName(keyboard)

        activeKeyboard = keyboard
        activeKeyboardClass = clazz
        activeKeyboardName = name
        digitKeyboardActive = isDigit

        DebugLog.i("keyboard activated: class=$clazz name=$name isDigits=$isDigit")
        ImeState.refreshNow()
    }

    private fun onDeactivate(keyboard: Any?) {
        if (keyboard == null) {
            return
        }

        // A new keyboard can be activated before the old one is deactivated. Only the
        // currently active object may clear the state.
        if (activeKeyboard !== keyboard) {
            DebugLog.i("keyboard deactivated (not active): ${keyboard.javaClass.name}")
            return
        }

        DebugLog.i(
            "keyboard deactivated: class=$activeKeyboardClass " +
                "name=$activeKeyboardName wasDigits=$digitKeyboardActive"
        )

        activeKeyboard = null
        activeKeyboardClass = null
        activeKeyboardName = null
        digitKeyboardActive = false
    }

    private fun keyboardName(keyboard: Any): String? {
        return try {
            XposedHelpers.callMethod(keyboard, "cz") as? String
        } catch (t: Throwable) {
            DebugLog.w("keyboard name lookup failed", t)
            null
        }
    }

    fun isDigitKeyboardActive(): Boolean = digitKeyboardActive

    fun activeClass(): String? = activeKeyboardClass

    fun activeName(): String? = activeKeyboardName
}
