package dev.ria.gboardtweak

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.WeakHashMap

/**
 * Gboard does not rebuild the comma/period key labels when the Chinese 9-key digit
 * page is toggled: the same full-width labels stay on screen. This class keeps track
 * of Gboard's SoftKeyView instances and, on a digit-page transition, rewrites/restores
 * the punctuation labels so the key faces match the committed text.
 *
 * It also repurposes the first four entries of the left punctuation strip
 * (`ReadingTextCandidateHolderView`) as +, -, ×, ÷ while the digit page is shown.
 */
object KeyLabelHooks {
    private const val STRIP_HOLDER_CLASS_SUFFIX = "ReadingTextCandidateHolderView"
    private const val MAX_RETRIES = 8
    private const val RETRY_DELAY_MS = 50L

    private val mainHandler = Handler(Looper.getMainLooper())

    // SoftKeyView constructor chaining makes the constructor hook fire three times
    // per instance. Key by identity so each SoftKeyView is tracked once.
    private val softKeyViews: MutableSet<View> =
        Collections.synchronizedSet(
            Collections.newSetFromMap(WeakHashMap<View, Boolean>())
        )

    // Original full-width label, used to restore the letter page on exit.
    private val convertedLabels =
        Collections.synchronizedMap(WeakHashMap<TextView, CharSequence>())

    // Original punctuation label -> operator face for the left strip.
    private val operatorLabels =
        Collections.synchronizedMap(WeakHashMap<TextView, OperatorLabel>())

    private var retryAttempts = 0

    private data class OperatorLabel(
        val original: CharSequence,
        val symbol: String
    )

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
                softKeyViews.add(view)
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
            applyDigitMode(active)
        } else {
            mainHandler.post { applyDigitMode(active) }
        }
    }

    private fun applyDigitMode(active: Boolean) {
        if (active) {
            retryAttempts = 0
            refreshLabels(true)
            applyOperatorLabels()
        } else {
            restoreOperatorLabels()
            MathSymbols.clear()
            refreshLabels(false)
        }
    }

    private fun refreshLabels(active: Boolean) {
        var changed = 0
        val views = synchronized(softKeyViews) { softKeyViews.toList() }

        for (view in views) {
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

    /**
     * Finds the punctuation strip's first four visible SoftKeyViews, records their
     * current labels, and replaces the faces with +, -, ×, ÷ in visual order.
     */
    private fun applyOperatorLabels() {
        if (operatorLabels.isNotEmpty()) {
            return
        }

        val holders = LinkedHashMap<View, MutableList<View>>()
        val views = synchronized(softKeyViews) { softKeyViews.toList() }

        for (view in views) {
            if (view.visibility != View.VISIBLE || !view.isShown()) {
                continue
            }
            val holder = stripHolder(view) ?: continue
            holders.getOrPut(holder) { mutableListOf() }.add(view)
        }

        if (holders.isEmpty()) {
            scheduleRetry()
            return
        }

        val mappings = LinkedHashMap<String, String>()
        val pending = mutableListOf<Pair<TextView, OperatorLabel>>()

        for (views in holders.values) {
            views.sortBy(::indexInParent)
            val selected = views.take(MathSymbols.SLOT_COUNT)
            if (selected.size < MathSymbols.SLOT_COUNT) {
                scheduleRetry()
                return
            }

            for ((index, view) in selected.withIndex()) {
                val labelView = labelTextView(view)
                val original = labelView?.text?.toString()
                if (labelView == null || original.isNullOrEmpty()) {
                    // The strip may not be fully populated yet; try again next frame.
                    scheduleRetry()
                    return
                }

                val symbol = MathSymbols.displaySymbols[index]
                pending += labelView to OperatorLabel(original, symbol)
                for (variant in MathSymbols.variants(original)) {
                    mappings[variant] = symbol
                }
            }
        }

        if (pending.isEmpty()) {
            scheduleRetry()
            return
        }

        for ((labelView, label) in pending) {
            labelView.text = label.symbol
            operatorLabels[labelView] = label
        }
        MathSymbols.replaceMappings(mappings)

        retryAttempts = 0
        DebugLog.i("digit operators applied: $mappings")
    }

    private fun restoreOperatorLabels() {
        if (operatorLabels.isEmpty()) {
            return
        }
        for ((labelView, label) in operatorLabels.entries.toList()) {
            if (labelView.text?.toString() == label.symbol) {
                labelView.text = label.original
            }
        }
        operatorLabels.clear()
    }

    private fun scheduleRetry() {
        if (retryAttempts >= MAX_RETRIES) {
            return
        }
        retryAttempts++
        mainHandler.postDelayed({
            if (KeyboardStateTracker.isDigitModeActive()) {
                applyOperatorLabels()
            }
        }, RETRY_DELAY_MS)
    }

    private fun stripHolder(view: View): View? {
        var parent = view.parent
        while (parent is View) {
            if (parent.javaClass.name.endsWith(STRIP_HOLDER_CLASS_SUFFIX)) {
                return parent
            }
            parent = parent.parent
        }
        return null
    }

    private fun indexInParent(view: View): Int {
        val parent = view.parent as? ViewGroup ?: return Int.MAX_VALUE
        return parent.indexOfChild(view)
    }

    private fun labelTextView(view: View): TextView? {
        if (view is TextView && view.text?.isNotEmpty() == true) {
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = labelTextView(view.getChildAt(i))
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }
}
