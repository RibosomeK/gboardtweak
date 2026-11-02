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

    fun escape(text: CharSequence): String = buildString(text.length) {
        for (ch in text) {
            when {
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch.code < 0x20 || ch.code == 0x7f -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
    }
}
