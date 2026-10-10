package io.github.daddymean.agentickeyboard.util

import android.util.Log

/**
 * KEYBOARD-021: error logging that cannot leak what the user typed.
 *
 * A keyboard sees everything the user types, and exceptions from the network,
 * JSON, database and on-device AI layers can carry request or response text,
 * clipboard content or typed words in their message, cause chain or stack
 * trace. `proguard-rules.pro` strips only Log.v/d/i, so Log.e/Log.w (and any
 * Throwable passed to them) reach release logcat.
 *
 * Every call here writes the caller's static [message] plus the exception's
 * class name only, never its message, cause or stack trace. Pass a static
 * message: no user text, response text or exception message in it.
 * `SafeLogGuardTest` scans the sources and fails if a log call passes a
 * Throwable to android.util.Log or logs an exception's message.
 */
object SafeLog {
    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, format(message, error))
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, format(message, error))
    }

    fun i(tag: String, message: String, error: Throwable? = null) {
        Log.i(tag, format(message, error))
    }

    /** "message [ExceptionClass]"; the exception contributes only its type name. */
    fun format(message: String, error: Throwable?): String =
        if (error == null) message else "$message [${errorName(error)}]"

    /** The simple class name (e.g. `IOException`), or `Throwable` for anonymous classes. */
    fun errorName(error: Throwable): String =
        error.javaClass.simpleName.ifBlank { "Throwable" }
}
