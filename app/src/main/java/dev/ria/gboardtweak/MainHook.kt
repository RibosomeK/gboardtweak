package dev.ria.gboardtweak

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != Constants.TARGET_PACKAGE) {
            return
        }

        XposedBridge.log(
            "[${Constants.TAG}] loaded in package=${lpparam.packageName} " +
                "process=${lpparam.processName}"
        )

        try {
            KeyboardStateTracker.install(lpparam.classLoader)
        } catch (t: Throwable) {
            DebugLog.e("KeyboardStateTracker installation failed", t)
            XposedBridge.log(t)
        }

        try {
            InputConnectionHooks.install(lpparam.classLoader)
        } catch (t: Throwable) {
            DebugLog.e("InputConnectionHooks installation failed", t)
            XposedBridge.log(t)
        }

        try {
            KeyLabelHooks.install(lpparam.classLoader)
        } catch (t: Throwable) {
            DebugLog.e("KeyLabelHooks installation failed", t)
            XposedBridge.log(t)
        }
    }
}
