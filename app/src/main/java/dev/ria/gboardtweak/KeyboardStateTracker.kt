package dev.ria.gboardtweak

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * The Chinese 9-key IME renders its letter/digit pages with
 * com.google.android.apps.inputmethod.latin.keyboard.LatinKeyboardWithNumberMode.
 * The up-arrow does not switch Keyboard objects; it toggles a state bit on the same
 * keyboard. LatinKeyboardWithNumberMode uses bit 0x2 to derive the
 * "Digit mode enabled/disabled" label, so that bit is used as the digit-page signal.
 *
 * This class also logs the layout-changing key events and state transitions needed to
 * verify the signal on a real device.
 */
object KeyboardStateTracker {
    private const val EXTRA_STATE_BEFORE = "gboardtweak.stateBefore"
    private const val EXTRA_ACTION = "gboardtweak.action"
    private const val EXTRA_DATA = "gboardtweak.data"

    private val LAYOUT_ACTIONS = setOf(
        -10004, // SWITCH_KEYBOARD
        -10025, // KEYBOARD_STATE_ON
        -10026, // KEYBOARD_STATE_OFF
        -10041, // SWITCH_SUB_CATEGORY
        -10043, // ALPHABET
        -10027,
        -10009,
        -10002,
        55, // COMMA
        56, // PERIOD
    )

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

        DebugLog.i("keyboard lifecycle hooks installed on ${keyboardClass.name}")

        installKeyEventLogging(classLoader)
    }

    private fun installKeyEventLogging(classLoader: ClassLoader) {
        val primeClass = XposedHelpers.findClassIfExists(
            Constants.LATIN_PRIME_KEYBOARD_CLASS,
            classLoader
        )

        if (primeClass == null) {
            DebugLog.w("keyboard event class not found: ${Constants.LATIN_PRIME_KEYBOARD_CLASS}")
            return
        }

        XposedBridge.hookAllMethods(primeClass, "m", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                captureKeyEvent(param)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                logStateChangingKeyEvent(param)
            }
        })

        DebugLog.i("keyboard event logging installed on ${primeClass.name}")
    }

    private fun captureKeyEvent(param: XC_MethodHook.MethodHookParam) {
        val keyboard = param.thisObject ?: return
        val eventCarrier = param.args.getOrNull(0) ?: return

        val event = try {
            XposedHelpers.callMethod(eventCarrier, "g")
        } catch (t: Throwable) {
            return
        } ?: return

        val actionId = try {
            XposedHelpers.getIntField(event, "d")
        } catch (t: Throwable) {
            return
        }

        val data = try {
            XposedHelpers.getObjectField(event, "f")
        } catch (t: Throwable) {
            null
        }
        val dataText = data as? CharSequence
        val isTarget = PunctuationTransformer.isTarget(dataText)

        param.setObjectExtra(EXTRA_STATE_BEFORE, readState(keyboard))
        param.setObjectExtra(EXTRA_ACTION, actionId)
        param.setObjectExtra(EXTRA_DATA, dataText?.toString() ?: "")

        if (actionId >= 0 && !isTarget) {
            return
        }

        val dataLog = dataLog(actionId, dataText, isTarget)
        DebugLog.i(
            "key event: class=${keyboard.javaClass.name} action=$actionId$dataLog " +
                stateDescription(keyboard)
        )
    }

    private fun logStateChangingKeyEvent(param: XC_MethodHook.MethodHookParam) {
        val keyboard = param.thisObject ?: return
        val before = param.getObjectExtra(EXTRA_STATE_BEFORE) as? Long ?: return
        val after = readState(keyboard)
        if (before < 0 || after < 0 || before == after) {
            return
        }

        val actionId = param.getObjectExtra(EXTRA_ACTION) as? Int ?: -1
        val data = param.getObjectExtra(EXTRA_DATA) as? String ?: ""
        val dataText: CharSequence? = if (data.isEmpty()) null else data
        val isTarget = PunctuationTransformer.isTarget(dataText)
        val dataLog = dataLog(actionId, dataText, isTarget)

        DebugLog.i(
            "key event changed state: class=${keyboard.javaClass.name} action=$actionId$dataLog " +
                "before=0x${before.toString(16)} " +
                stateDescription(keyboard, after)
        )
    }

    private fun dataLog(
        actionId: Int,
        dataText: CharSequence?,
        isTarget: Boolean
    ): String {
        return when {
            isTarget -> " data='${DebugLog.escape(dataText!!)}'"
            actionId in LAYOUT_ACTIONS && isSafeToken(dataText) ->
                " data='${DebugLog.escape(dataText!!)}'"
            else -> ""
        }
    }

    private fun isSafeToken(text: CharSequence?): Boolean {
        if (text == null || text.isEmpty() || text.length > 32) {
            return false
        }
        return text.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == ' ' }
    }

    private fun onActivate(keyboard: Any?) {
        if (keyboard == null) {
            return
        }

        val clazz = keyboard.javaClass.name
        val name = keyboardName(keyboard)
        val state = readState(keyboard)
        if (state >= 0) {
            activeState = state
        }

        activeKeyboard = keyboard
        activeKeyboardClass = clazz
        activeKeyboardName = name

        DebugLog.i("keyboard activated: class=$clazz name=$name ${stateDescription(keyboard, state)}")
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
            "keyboard deactivated: class=$activeKeyboardClass name=$activeKeyboardName " +
                stateDescription(keyboard)
        )

        activeKeyboard = null
        activeKeyboardClass = null
        activeKeyboardName = null
        activeState = 0L
    }

    private fun onStateChange(param: XC_MethodHook.MethodHookParam) {
        val keyboard = param.thisObject ?: return
        if (keyboard !== activeKeyboard) {
            return
        }

        val old = param.args.getOrNull(0) as? Long ?: return
        val new = param.args.getOrNull(1) as? Long ?: return
        val live = readState(keyboard)
        if (live >= 0) {
            activeState = live
        }

        DebugLog.i(
            "keyboard state: old=0x${old.toString(16)} new=0x${new.toString(16)} " +
                stateDescription(keyboard, live)
        )
    }

    private fun keyboardName(keyboard: Any): String? {
        return try {
            XposedHelpers.callMethod(keyboard, "cz") as? String
        } catch (t: Throwable) {
            DebugLog.w("keyboard name lookup failed", t)
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

    private fun stateDescription(keyboard: Any, stateOverride: Long? = null): String {
        val state = stateOverride ?: readState(keyboard)
        val safeState = if (state >= 0) state else 0L
        val mask = Constants.DIGIT_MODE_STATE_MASK
        val sub = safeState and Constants.SUB_CATEGORY_STATE_MASK
        return "state=0x${safeState.toString(16)} " +
            "digitPage=${(safeState and mask) == mask} " +
            "sub=0x${sub.toString(16)}"
    }

    /**
     * True while the active Chinese number-mode keyboard is showing its digit page.
     *
     * The page is not a separate Keyboard object. On Gboard 18.5.4 the caps-lock state
     * bits (CAPS | CAPS_LOCK_MASK) are set on the digit page and cleared on the letter
     * page, so both bits must be present.
     */
    fun isDigitModeActive(): Boolean {
        val keyboard = activeKeyboard ?: return false
        if (keyboard.javaClass.name != Constants.NUMBER_MODE_KEYBOARD_CLASS) {
            return false
        }

        val live = readState(keyboard)
        if (live >= 0) {
            activeState = live
        }
        val mask = Constants.DIGIT_MODE_STATE_MASK
        return (activeState and mask) == mask
    }

    fun activeClass(): String? = activeKeyboardClass

    fun activeName(): String? = activeKeyboardName

    fun stateDescription(): String {
        val keyboard = activeKeyboard ?: return "state=<none>"
        return stateDescription(keyboard)
    }
}
