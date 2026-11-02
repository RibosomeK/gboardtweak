package dev.ria.gboardtweak

import android.util.Log

object DebugLog {
    fun i(message: String) {
        if (Constants.DEBUG) {
            Log.i(Constants.TAG, message)
        }
    }

    fun w(message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.w(Constants.TAG, message)
        } else {
            Log.w(Constants.TAG, message, throwable)
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.e(Constants.TAG, message)
        } else {
            Log.e(Constants.TAG, message, throwable)
        }
    }
}
