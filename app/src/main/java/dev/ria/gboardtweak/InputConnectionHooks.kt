package dev.ria.gboardtweak

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.Collections

/**
 * There are two independent commit hooks:
 *
 * 1. Primary: discover the concrete InputConnection returned by InputMethodService and
 *    hook its commitText/setComposingText overloads. The class is a framework class whose
 *    method names are stable across Gboard updates.
 * 2. Fallback: hook Gboard 18.5.4's own commit wrapper (aekp.q / aemg.b), which are
 *    obfuscated and version-specific, but catch the commit before it is queued.
 *
 * Conversion only happens when the active Keyboard is LatinDigitsKeyboard, the IME
 * subtype is Chinese, and the entire committed text is exactly one target character.
 */
object InputConnectionHooks {
    private val TEXT_METHODS = setOf("commitText", "setComposingText")

    private val hookedClassNames =
        Collections.synchronizedSet(mutableSetOf<String>())
    private val hookedMethodKeys =
        Collections.synchronizedSet(mutableSetOf<String>())

    fun install(classLoader: ClassLoader) {
        installInputConnectionDiscovery(classLoader)
        installGboardFallbacks(classLoader)
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
                        ImeState.onServiceSeen(param.thisObject)
                        val connection = param.result ?: return
                        ensureInputConnectionHooked(connection.javaClass)
                    }
                }
            )
            DebugLog.i("InputMethodService.getCurrentInputConnection hook installed")
        } catch (t: Throwable) {
            DebugLog.e("failed to hook getCurrentInputConnection", t)
        }
    }

    private fun ensureInputConnectionHooked(connectionClass: Class<*>) {
        if (!hookedClassNames.add(connectionClass.name)) {
            return
        }

        var hookedCount = 0
        var cursor: Class<*>? = connectionClass

        while (cursor != null && cursor != Any::class.java) {
            for (method in cursor.declaredMethods) {
                if (method.name !in TEXT_METHODS) {
                    continue
                }
                if (method.parameterTypes.isEmpty()) {
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
                        TextMethodHook("InputConnection.${method.name}")
                    )
                    hookedCount++
                    DebugLog.i(
                        "hooked ${cursor.name}.${method.name}(" +
                            method.parameterTypes.joinToString(", ") { it.simpleName } +
                            ")"
                    )
                } catch (t: Throwable) {
                    DebugLog.e(
                        "failed to hook ${cursor.name}.${method.name}",
                        t
                    )
                }
            }
            cursor = cursor.superclass
        }

        DebugLog.i(
            "discovered InputConnection class ${connectionClass.name}, " +
                "hooked $hookedCount text methods"
        )
    }

    private fun methodKey(owner: Class<*>, method: Method): String {
        return owner.name + "#" + method.name + "(" +
            method.parameterTypes.joinToString(",") { it.name } + ")"
    }

    private fun installGboardFallbacks(classLoader: ClassLoader) {
        hookNamedMethod(
            classLoader,
            Constants.GBOARD_COMMIT_WRAPPER_CLASS,
            "q",
            "aekp.q"
        )
        hookNamedMethod(
            classLoader,
            Constants.GBOARD_COMMIT_QUEUE_CLASS,
            "b",
            "aemg.b"
        )
    }

    private fun hookNamedMethod(
        classLoader: ClassLoader,
        className: String,
        methodName: String,
        source: String
    ) {
        val clazz = XposedHelpers.findClassIfExists(className, classLoader)
        if (clazz == null) {
            DebugLog.w("fallback class not found: $className")
            return
        }

        val methods = clazz.declaredMethods.filter { method ->
            method.name == methodName &&
                method.parameterTypes.any {
                    CharSequence::class.java.isAssignableFrom(it)
                }
        }

        if (methods.isEmpty()) {
            DebugLog.w("fallback method not found: $className.$methodName")
            return
        }

        for (method in methods) {
            try {
                XposedBridge.hookMethod(method, TextMethodHook(source))
                DebugLog.i("fallback hook installed: $source (${clazz.name})")
            } catch (t: Throwable) {
                DebugLog.e("failed to install fallback hook: $source", t)
            }
        }
    }

    private class TextMethodHook(
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

            val digit = KeyboardStateTracker.isDigitKeyboardActive()
            val chinese = ImeState.isChinese()

            DebugLog.i(
                "$source sees '${DebugLog.escape(text)}' " +
                    "digit=$digit zh=$chinese " +
                    "keyboard=${KeyboardStateTracker.activeClass()} " +
                    "name=${KeyboardStateTracker.activeName()}"
            )

            if (!digit || !chinese) {
                return
            }

            val converted = PunctuationTransformer.transform(text)
            param.args[index] = converted
            DebugLog.i(
                "$source converted '${DebugLog.escape(text)}' -> " +
                    "'${DebugLog.escape(converted)}'"
            )
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
