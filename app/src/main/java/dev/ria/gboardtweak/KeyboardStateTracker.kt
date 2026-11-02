package dev.ria.gboardtweak

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Tracks the active Gboard keyboard and whether its Chinese 9-key digit page is shown.
 *
 * The up-arrow page is not a separate Keyboard object. Gboard keeps
 * LatinKeyboardWithNumberMode active and toggles the CAPS | CAPS_LOCK_MASK state bits
 * (0x60000000000). The letter page has neither bit set.
 */
object KeyboardStateTracker {
    @Volatile
    private var activeKeyboard: Any? = null

    @Volatile
    private var activeState: Long = 0L

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

        XposedBridge.hookAllMethods(keyboardClass, "cF", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                onStateChange(param)
            }
        })

        DebugLog.i("keyboard state tracker installed")
    }

    private fun onActivate(keyboard: Any?) {
        if (keyboard == null) {
            return
        }

        activeKeyboard = keyboard
        activeKeyboardClass = keyboard.javaClass.name
        activeKeyboardName = keyboardName(keyboard)
        updateState(keyboard)

        DebugLog.i(
            "keyboard activated: ${activeKeyboardClass} " +
                "name=${activeKeyboardName} digit=${isDigitModeActive()}"
        )

        // Restore any label left over from a previous session; usually a no-op.
        KeyLabelHooks.onDigitModeChanged(isDigitModeActive())
    }

    private fun onDeactivate(keyboard: Any?) {
        if (keyboard == null || activeKeyboard !== keyboard) {
            return
        }

        DebugLog.i("keyboard deactivated: $activeKeyboardClass")

        activeKeyboard = null
        activeKeyboardClass = null
        activeKeyboardName = null
        activeState = 0L

        KeyLabelHooks.onDigitModeChanged(false)
    }

    private fun onStateChange(param: XC_MethodHook.MethodHookParam) {
        val keyboard = param.thisObject ?: return
        if (keyboard !== activeKeyboard) {
            return
        }

        val oldState = param.args.getOrNull(0) as? Long ?: return
        val newState = param.args.getOrNull(1) as? Long ?: return

        updateState(keyboard)

        val wasDigitPage = isDigitPageState(oldState)
        val isDigitPage = isDigitPageState(newState)
        if (wasDigitPage != isDigitPage) {
            DebugLog.i("digit page changed: $wasDigitPage -> $isDigitPage")
            KeyLabelHooks.onDigitModeChanged(isDigitPage)
        }
    }

    private fun updateState(keyboard: Any) {
        val state = readState(keyboard)
        if (state >= 0) {
            activeState = state
        }
    }

    private fun keyboardName(keyboard: Any): String? {
        return try {
            XposedHelpers.callMethod(keyboard, "cz") as? String
        } catch (t: Throwable) {
            null
        }
    }

    private fun readState(keyboard: Any): Long {
        return try {
            XposedHelpers.getLongField(keyboard, "F")
        } catch (t: Throwable) {
            -1L
        }
    }

    private fun isDigitPageState(state: Long): Boolean {
        val mask = Constants.DIGIT_MODE_STATE_MASK
        return (state and mask) == mask
    }

    fun isDigitModeActive(): Boolean {
        val keyboard = activeKeyboard ?: return false
        if (keyboard.javaClass.name != Constants.NUMBER_MODE_KEYBOARD_CLASS) {
            return false
        }

        val state = readState(keyboard)
        if (state >= 0) {
            activeState = state
        }
        return isDigitPageState(activeState)
    }

    fun activeName(): String? = activeKeyboardName
}
