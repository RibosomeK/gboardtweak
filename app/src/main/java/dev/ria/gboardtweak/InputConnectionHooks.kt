package dev.ria.gboardtweak

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.Collections

/**
 * Intercepts committed text in two places:
 *
 * 1. The concrete InputConnection returned by InputMethodService. Its class is
 *    discovered at runtime and commitText() is hooked. This is the version-independent
 *    path.
 * 2. Gboard 18.5.4's own InputConnectionWrapper.commitText (obfuscated aekp.q), which
 *    runs before the framework call. This is the path verified on-device.
 *
 * Text is rewritten only when all of these are true:
 * - the active keyboard is LatinKeyboardWithNumberMode showing its digit page;
 * - the active keyboard is a Chinese keyboard;
 * - the whole committed text is exactly U+FF0C or U+3002.
 */
object InputConnectionHooks {
    private val hookedClassNames =
        Collections.synchronizedSet(mutableSetOf<String>())
    private val hookedMethodKeys =
        Collections.synchronizedSet(mutableSetOf<String>())

    fun install(classLoader: ClassLoader) {
        installInputConnectionDiscovery(classLoader)
        installGboardWrapperFallback(classLoader)
    }

    private fun installInputConnectionDiscovery(classLoader: ClassLoader) {
        val serviceClass = XposedHelpers.findClassIfExists(
            Constants.INPUT_METHOD_SERVICE_CLASS,
            classLoader
        )

        if (serviceClass == null) {
            DebugLog.e("InputMethodService class not found")
            return
        }

        try {
            XposedBridge.hookAllMethods(
                serviceClass,
                "getCurrentInputConnection",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val connection = param.result ?: return
                        ensureInputConnectionHooked(connection.javaClass)
                    }
                }
            )
            DebugLog.i("InputConnection discovery installed")
        } catch (t: Throwable) {
            DebugLog.e("failed to hook getCurrentInputConnection", t)
        }
    }

    private fun ensureInputConnectionHooked(connectionClass: Class<*>) {
        if (!hookedClassNames.add(connectionClass.name)) {
            return
        }

        var cursor: Class<*>? = connectionClass
        while (cursor != null && cursor != Any::class.java) {
            for (method in cursor.declaredMethods) {
                if (method.name != "commitText" || method.parameterTypes.isEmpty()) {
                    continue
                }
                if (!CharSequence::class.java.isAssignableFrom(method.parameterTypes[0])) {
                    continue
                }

                val key = methodKey(cursor, method)
                if (!hookedMethodKeys.add(key)) {
                    continue
                }

                try {
                    XposedBridge.hookMethod(
                        method,
                        CommitTextHook("InputConnection.commitText")
                    )
                    DebugLog.i(
                        "hooked ${cursor.name}.commitText(" +
                            method.parameterTypes.joinToString(", ") { it.simpleName } +
                            ")"
                    )
                } catch (t: Throwable) {
                    DebugLog.e("failed to hook ${cursor.name}.commitText", t)
                }
            }
            cursor = cursor.superclass
        }
    }

    private fun installGboardWrapperFallback(classLoader: ClassLoader) {
        val clazz = XposedHelpers.findClassIfExists(
            Constants.GBOARD_COMMIT_WRAPPER_CLASS,
            classLoader
        )

        if (clazz == null) {
            DebugLog.w("commit wrapper not found: ${Constants.GBOARD_COMMIT_WRAPPER_CLASS}")
            return
        }

        val methods = clazz.declaredMethods.filter { method ->
            method.name == "q" &&
                method.parameterTypes.any {
                    CharSequence::class.java.isAssignableFrom(it)
                }
        }

        if (methods.isEmpty()) {
            DebugLog.w("commit wrapper method not found")
            return
        }

        for (method in methods) {
            try {
                XposedBridge.hookMethod(
                    method,
                    CommitTextHook("Gboard.commitText")
                )
                DebugLog.i("Gboard commit wrapper hook installed")
            } catch (t: Throwable) {
                DebugLog.e("failed to hook Gboard commit wrapper", t)
            }
        }
    }

    private fun methodKey(owner: Class<*>, method: Method): String {
        return owner.name + "#" + method.name + "(" +
            method.parameterTypes.joinToString(",") { it.name } + ")"
    }

    private class CommitTextHook(
        private val source: String
    ) : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val index = indexOfCharSequence(param.args)
            if (index < 0) {
                return
            }

            val text = param.args[index] as CharSequence
            if (!PunctuationTransformer.isTarget(text)) {
                return
            }
            if (!KeyboardStateTracker.isDigitModeActive() || !ImeState.isChinese()) {
                return
            }

            val converted = PunctuationTransformer.transform(text)
            param.args[index] = converted
            DebugLog.i("$source converted '${text}' -> '${converted}'")
        }
    }

    private fun indexOfCharSequence(args: Array<Any?>): Int {
        for (i in args.indices) {
            if (args[i] is CharSequence) {
                return i
            }
        }
        return -1
    }
}
