package com.constx.onvyra.utils

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.constx.onvyra.BuildConfig

private const val TAG = "CrashHandler"
private const val PREFS_NAME = "crash_handler"
private const val KEY_CRASHED = "crashed"
private const val KEY_STACKTRACE = "stacktrace"
private const val MAX_STACKTRACE_LENGTH = 8000

object CrashHandler {
    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Android's default handler logs its Throwable too: sanitize the object passed to it,
            // rather than hiding only this app's own Logcat line.
            val diagnostic = if (BuildConfig.DEBUG) null else runCatching { sanitizeCrashReport(throwable) }
                .getOrElse {
                    val safe = RuntimeException("Uncaught release exception; diagnostic details unavailable")
                    safe.stackTrace = emptyArray()
                    SanitizedCrashReport("Uncaught release exception; diagnostic details unavailable", safe)
                }
            val reportedThrowable = diagnostic?.throwable ?: throwable
            val report = diagnostic?.text ?: buildString {
                appendLine("Thread: ${thread.name}")
                appendLine("Recent UI phases:")
                appendLine(UiCrashBreadcrumbs.snapshot())
                appendLine(throwable.stackTraceToString())
            }
            if (!BuildConfig.DEBUG) {
                // The platform prints the thread name in its FATAL EXCEPTION header.
                runCatching { thread.name = "Onvyra-release-crash" }
            }
            runCatching { Log.e(TAG, "Uncaught exception", reportedThrowable) }
            // A failed diagnostic write must not prevent the original process-termination handler.
            runCatching { markCrashed(appContext, report) }
            defaultHandler?.uncaughtException(thread, reportedThrowable)
        }
    }

    fun hasCrashed(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRASHED, false)
    }

    fun getStackTrace(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STACKTRACE, null)
    }

    fun clearCrashed(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_CRASHED).remove(KEY_STACKTRACE) }
    }

    private fun markCrashed(context: Context, report: String) {
        val stackTrace = buildString {
            appendLine("Version: ${BuildConfig.VERSION_NAME}")
            appendLine(report)
        }.take(MAX_STACKTRACE_LENGTH)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putBoolean(KEY_CRASHED, true)
                putString(KEY_STACKTRACE, stackTrace)
            } // commit() 同步写入，确保崩溃前写完
    }
}
