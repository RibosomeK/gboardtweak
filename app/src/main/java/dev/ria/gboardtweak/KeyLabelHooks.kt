package dev.ria.gboardtweak

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * Gboard does not rebuild the comma/period key labels when the Chinese 9-key digit
 * page is toggled: the same full-width labels stay on screen. This class keeps track
 * of Gboard's SoftKeyView instances and, on a digit-page transition, rewrites/restores
 * the punctuation labels so the key faces match the committed half-width text.
 */
object KeyLabelHooks {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val softKeyViews =
        Collections.synchronizedList(mutableListOf<WeakReference<View>>())

    // Original full-width label, used to restore the letter page on exit.
    private val convertedLabels =
        Collections.synchronizedMap(WeakHashMap<TextView, CharSequence>())

    @Volatile
    private var installed = false

    fun install(classLoader: ClassLoader) {
        val clazz = XposedHelpers.findClassIfExists(
            Constants.SOFT_KEY_VIEW_CLASS,
            classLoader
        )

        if (clazz == null) {
            DebugLog.w("SoftKeyView class not found: ${Constants.SOFT_KEY_VIEW_CLASS}")
            return
        }

        XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                softKeyViews.add(WeakReference(view))
            }
        })

        installed = true
        DebugLog.i("SoftKeyView tracker installed")
    }

    fun onDigitModeChanged(active: Boolean) {
        if (!installed) {
            return
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refreshLabels(active)
        } else {
            mainHandler.post { refreshLabels(active) }
        }
    }

    private fun refreshLabels(active: Boolean) {
        var changed = 0
        val iterator = softKeyViews.iterator()

        while (iterator.hasNext()) {
            val view = iterator.next().get()
            if (view == null) {
                iterator.remove()
                continue
            }
            changed += refreshViewTree(view, active)
        }

        if (changed > 0) {
            DebugLog.i("key labels updated: active=$active changed=$changed")
        }
    }

    private fun refreshViewTree(view: View, active: Boolean): Int {
        var changed = 0

        if (view is TextView) {
            changed += applyToTextView(view, active)
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                changed += refreshViewTree(view.getChildAt(i), active)
            }
        }

        return changed
    }

    private fun applyToTextView(view: TextView, active: Boolean): Int {
        val text = view.text ?: return 0

        if (active) {
            if (!PunctuationTransformer.isTarget(text)) {
                return 0
            }
            val converted = PunctuationTransformer.transform(text)
            if (convertedLabels[view] == null) {
                convertedLabels[view] = text
            }
            view.text = converted
            return 1
        }

        val original = convertedLabels.remove(view) ?: return 0
        if (isAsciiTarget(text)) {
            view.text = original
            return 1
        }
        return 0
    }

    private fun isAsciiTarget(text: CharSequence): Boolean {
        return text.length == 1 && (text[0] == ',' || text[0] == '.')
    }
}
